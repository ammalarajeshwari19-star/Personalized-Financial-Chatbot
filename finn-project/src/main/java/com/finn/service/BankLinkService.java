package com.finn.service;

import com.finn.dto.*;
import com.finn.model.*;
import com.finn.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class BankLinkService {

    private final LinkedAccountRepository  linkedAccountRepo;
    private final TransactionRepository    transactionRepo;
    private final CategoryRepository       categoryRepo;
    private final UserRepository           userRepo;
    private final BankSyncLogRepository    syncLogRepo;
    private final AccountAggregatorService aaService;

    // ─── Link Account ─────────────────────────────────────────────────────────

    @Transactional
    public BankLinkResponse linkAccount(BankLinkRequest req) {
        User user = userRepo.findById(req.getUserId()).orElseThrow();

        // Hash the account number — never store raw
        String acctHash = sha256(req.getAccountNumber());
        String lastFour = req.getAccountNumber().substring(req.getAccountNumber().length() - 4);

        // Mask and store card safely
        String cardLast4 = null;
        Integer cardMonth = null, cardYear = null;
        LinkedAccount.CardNetwork cardNetwork = LinkedAccount.CardNetwork.RUPAY;

        if (req.getCardNumber() != null && req.getCardNumber().replaceAll("\\s","").length() >= 16) {
            String raw = req.getCardNumber().replaceAll("\\s","");
            cardLast4   = raw.substring(raw.length() - 4);
            cardMonth   = req.getCardExpiryMonth();
            cardYear    = req.getCardExpiryYear();
            cardNetwork = detectNetwork(raw);
            // raw card number is DISCARDED here — never persisted
        }

        LinkedAccount account = LinkedAccount.builder()
            .user(user)
            .accountNumberHash(acctHash)
            .accountLastFour(lastFour)
            .accountType(LinkedAccount.AccountType.valueOf(req.getAccountType()))
            .ifscCode(req.getIfscCode().toUpperCase())
            .bankName(req.getBankName())
            .branchName(req.getBranchName())
            .holderName(req.getHolderName())
            .upiId(req.getUpiId())
            .cardLastFour(cardLast4)
            .cardExpiryMonth(cardMonth)
            .cardExpiryYear(cardYear)
            .cardNetwork(cardNetwork)
            .aaFipId(mapBankToFipId(req.getBankName()))
            .aaConsentStatus(LinkedAccount.ConsentStatus.PENDING)
            .primary(linkedAccountRepo.countByUserId(req.getUserId()) == 0)
            .active(true)
            .build();

        linkedAccountRepo.save(account);

        // Initiate AA consent request
        AAConsentResponse consent = aaService.createConsentRequest(account,
            user.getMobile() != null ? user.getMobile() : req.getMobileForAA());

        account.setAaConsentId(consent.getConsentHandle());
        linkedAccountRepo.save(account);

        return BankLinkResponse.builder()
            .accountId(account.getId())
            .maskedAccount("XXXX XXXX " + lastFour)
            .bankName(account.getBankName())
            .aaConsentHandle(consent.getConsentHandle())
            .aaRedirectUrl(consent.getRedirectUrl())
            .consentStatus("PENDING")
            .message(consent.isMock()
                ? "Demo mode: AA sandbox not configured. Transactions will be simulated."
                : "Please complete consent in your bank's AA app to enable auto-sync.")
            .build();
    }

    // ─── AA Callback (after user approves consent) ───────────────────────────

    @Transactional
    public SyncResponse handleAACallback(String consentHandle, boolean approved) {
        LinkedAccount account = linkedAccountRepo.findByAaConsentId(consentHandle)
            .orElseThrow(() -> new RuntimeException("Unknown consent handle"));

        if (approved) {
            account.setAaConsentStatus(LinkedAccount.ConsentStatus.ACTIVE);
            linkedAccountRepo.save(account);
            // Trigger first sync
            return syncAccount(account.getId());
        } else {
            account.setAaConsentStatus(LinkedAccount.ConsentStatus.REVOKED);
            linkedAccountRepo.save(account);
            return SyncResponse.builder().success(false).message("Consent rejected by user.").build();
        }
    }

    // ─── Sync Transactions ────────────────────────────────────────────────────

    @Transactional
    public SyncResponse syncAccount(Long accountId) {
        LinkedAccount account = linkedAccountRepo.findById(accountId).orElseThrow();

        BankSyncLog log = BankSyncLog.builder()
            .linkedAccount(account)
            .syncType(BankSyncLog.SyncType.AA_FETCH)
            .status(BankSyncLog.SyncStatus.STARTED)
            .startedAt(LocalDateTime.now())
            .build();
        syncLogRepo.save(log);

        try {
            LocalDate from = account.getLastSyncedAt() != null
                ? account.getLastSyncedAt().toLocalDate()
                : LocalDate.now().minusMonths(6);
            LocalDate to = LocalDate.now();

            List<FetchedTransaction> fetched = aaService.fetchTransactions(account, from, to);
            int saved = saveTransactions(account, fetched);

            account.setLastSyncedAt(LocalDateTime.now());
            linkedAccountRepo.save(account);

            log.setStatus(BankSyncLog.SyncStatus.SUCCESS);
            log.setTransactionsFetched(saved);
            log.setCompletedAt(LocalDateTime.now());
            syncLogRepo.save(log);

            return SyncResponse.builder()
                .success(true)
                .transactionsFetched(saved)
                .message(saved + " new transactions synced from " + account.getBankName())
                .build();

        } catch (Exception e) {
            log.setStatus(BankSyncLog.SyncStatus.FAILED);
            log.setErrorMessage(e.getMessage());
            log.setCompletedAt(LocalDateTime.now());
            syncLogRepo.save(log);
            return SyncResponse.builder().success(false).message("Sync failed: " + e.getMessage()).build();
        }
    }

    // ─── Save Fetched Transactions ────────────────────────────────────────────

    private int saveTransactions(LinkedAccount account, List<FetchedTransaction> fetched) {
        List<Category> categories = categoryRepo.findByUserId(account.getUser().getId());
        int count = 0;

        for (FetchedTransaction ft : fetched) {
            // Skip duplicates
            if (ft.getRefId() != null &&
                transactionRepo.existsByUserIdAndTxnRefId(account.getUser().getId(), ft.getRefId())) {
                continue;
            }

            Category cat = autoCategory(ft.getDescription(), ft.getMerchantName(), categories);

            Transaction txn = Transaction.builder()
                .user(account.getUser())
                .linkedAccount(account)
                .category(cat)
                .description(ft.getDescription())
                .amount(BigDecimal.valueOf(ft.getAmount()))
                .type(Transaction.TransactionType.valueOf(ft.getType()))
                .txnDate(ft.getDate())
                .txnRefId(ft.getRefId())
                .source(Transaction.Source.AA_FETCH)
                .merchantName(ft.getMerchantName())
                .build();

            transactionRepo.save(txn);
            count++;
        }
        return count;
    }

    // ─── Smart Auto-Categorisation ────────────────────────────────────────────

    private Category autoCategory(String desc, String merchant, List<Category> cats) {
        String text = ((desc != null ? desc : "") + " " + (merchant != null ? merchant : "")).toLowerCase();

        Map<String, String[]> rules = new LinkedHashMap<>();
        rules.put("Food",          new String[]{"swiggy","zomato","food","restaurant","cafe","dominos","mcdonalds","bigbasket","groceries","dunzo"});
        rules.put("Transport",     new String[]{"ola","uber","rapido","cab","metro","irctc","bus","train","petrol","fuel","parking"});
        rules.put("Entertainment", new String[]{"bookmyshow","pvr","inox","movie","netflix","hotstar","prime","spotify","amazon music","gaming"});
        rules.put("Shopping",      new String[]{"amazon","flipkart","myntra","ajio","nykaa","meesho","shopping","mall","store"});
        rules.put("Health",        new String[]{"pharmacy","medplus","apollo","hospital","doctor","clinic","lab","diagnostic","medicine","health"});
        rules.put("Utilities",     new String[]{"electricity","water","gas","broadband","jio","airtel","vi","bsnl","rent","maintenance","bill"});
        rules.put("Subscriptions", new String[]{"subscription","netflix","hotstar","prime","spotify","youtube","adobe","microsoft"});
        rules.put("ATM Withdrawal",new String[]{"atm","cash withdrawal","wdl"});
        rules.put("UPI Transfer",  new String[]{"salary","credit","received","transfer in","imps","neft"});

        for (Map.Entry<String, String[]> rule : rules.entrySet()) {
            for (String kw : rule.getValue()) {
                if (text.contains(kw)) {
                    final String catName = rule.getKey();
                    return cats.stream()
                        .filter(c -> c.getName().equalsIgnoreCase(catName))
                        .findFirst()
                        .orElseGet(() -> cats.stream().filter(c -> c.getName().equals("Other")).findFirst().orElse(cats.get(0)));
                }
            }
        }
        return cats.stream().filter(c -> c.getName().equals("Other")).findFirst().orElse(cats.get(0));
    }

    // ─── Utils ────────────────────────────────────────────────────────────────

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return input; // fallback (should never happen)
        }
    }

    private LinkedAccount.CardNetwork detectNetwork(String cardNumber) {
        if (cardNumber.startsWith("4"))                          return LinkedAccount.CardNetwork.VISA;
        if (cardNumber.startsWith("5") || cardNumber.startsWith("2")) return LinkedAccount.CardNetwork.MASTERCARD;
        return LinkedAccount.CardNetwork.RUPAY; // 6-series
    }

    private String mapBankToFipId(String bankName) {
        Map<String, String> fipMap = Map.of(
            "SBI",    "SBI-FIP",
            "HDFC",   "HDFC-FIP",
            "ICICI",  "ICICI-FIP",
            "AXIS",   "AXIS-FIP",
            "KOTAK",  "KOTAK-FIP",
            "YES",    "YESBANK-FIP",
            "CANARA", "CANARA-FIP",
            "BOB",    "BOB-FIP"
        );
        return fipMap.entrySet().stream()
            .filter(e -> bankName.toUpperCase().contains(e.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse("GENERIC-FIP");
    }

    public List<LinkedAccountResponse> getLinkedAccounts(Long userId) {
        return linkedAccountRepo.findByUserIdAndActiveTrue(userId).stream()
            .map(a -> LinkedAccountResponse.builder()
                .id(a.getId())
                .maskedAccount("XXXX XXXX " + a.getAccountLastFour())
                .bankName(a.getBankName())
                .branchName(a.getBranchName())
                .ifscCode(a.getIfscCode())
                .holderName(a.getHolderName())
                .upiId(a.getUpiId())
                .cardMasked(a.getCardLastFour() != null ? "XXXX XXXX XXXX " + a.getCardLastFour() : null)
                .cardNetwork(a.getCardNetwork() != null ? a.getCardNetwork().name() : null)
                .consentStatus(a.getAaConsentStatus().name())
                .lastSyncedAt(a.getLastSyncedAt())
                .primary(a.isPrimary())
                .build())
            .toList();
    }
}
