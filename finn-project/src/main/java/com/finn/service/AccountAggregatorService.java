package com.finn.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finn.dto.BankLinkRequest;
import com.finn.dto.AAConsentResponse;
import com.finn.dto.FetchedTransaction;
import com.finn.model.LinkedAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * India Account Aggregator (AA) Integration
 *
 * Based on ReBIT / Sahamati standards:
 *   https://api.rebit.org.in/
 *   https://sahamati.org.in/
 *
 * Flow:
 *  1. POST /Consent           → get consentHandle
 *  2. Redirect user to AA app → user approves
 *  3. GET  /Consent/{handle}  → get consentId after approval
 *  4. POST /FI/request        → request financial data session
 *  5. GET  /FI/fetch/{id}     → fetch encrypted FI data
 *  6. Decrypt + parse transactions
 *
 * For production: register with a licensed AA (Finvu, OneMoney, CAMSfinserv, Perfios AA)
 * Sandbox testing: https://sandbox.finvu.in/
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AccountAggregatorService {

    @Value("${aa.base-url:https://sandbox.finvu.in/consentapi}")
    private String aaBaseUrl;

    @Value("${aa.client-id:YOUR_AA_CLIENT_ID}")
    private String clientId;

    @Value("${aa.client-secret:YOUR_AA_CLIENT_SECRET}")
    private String clientSecret;

    @Value("${aa.redirect-url:http://localhost:8080/api/bank/aa-callback}")
    private String redirectUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── STEP 1: Create Consent Request ──────────────────────────────────────

    /**
     * Creates a consent request with the AA framework.
     * Returns the consentHandle and redirect URL for the user to approve.
     *
     * Spec: ReBIT AA API v2.0.0 - POST /Consent
     */
    public AAConsentResponse createConsentRequest(LinkedAccount account, String mobileNumber) {
        try {
            WebClient client = buildClient();

            String consentStart  = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
            String consentExpiry = LocalDateTime.now().plusYears(1).format(DateTimeFormatter.ISO_DATE_TIME);
            String dataStart     = LocalDate.now().minusMonths(6).format(DateTimeFormatter.ISO_DATE);
            String dataEnd       = LocalDate.now().format(DateTimeFormatter.ISO_DATE);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ver", "2.0.0");
            body.put("timestamp", consentStart);
            body.put("txnid", UUID.randomUUID().toString());

            // Consent detail per ReBIT spec
            Map<String, Object> consentDetail = new LinkedHashMap<>();
            consentDetail.put("consentStart",  consentStart);
            consentDetail.put("consentExpiry", consentExpiry);
            consentDetail.put("consentMode",   "VIEW");
            consentDetail.put("fetchType",     "PERIODIC");
            consentDetail.put("consentTypes",  List.of("TRANSACTIONS", "SUMMARY", "PROFILE"));
            consentDetail.put("fiTypes",       List.of("DEPOSIT"));
            consentDetail.put("purpose", Map.of(
                "code",    "101",
                "refUri",  "https://api.rebit.org.in/aa/purpose/101.xml",
                "text",    "Wealth management service",
                "Category", Map.of("type", "Personal Finance")
            ));
            consentDetail.put("FIDataRange", Map.of(
                "from", dataStart,
                "to",   dataEnd
            ));
            consentDetail.put("DataLife", Map.of("unit", "MONTH", "value", 1));
            consentDetail.put("Frequency", Map.of("unit", "MONTH", "value", 1));
            consentDetail.put("DataFilter", List.of(
                Map.of("type", "TRANSACTIONAMOUNT", "operator", ">=", "value", "1")
            ));

            // Customer reference
            consentDetail.put("Customer", Map.of(
                "id", mobileNumber + "@finvu"  // AA virtual address
            ));

            body.put("ConsentDetail", consentDetail);

            String requestJson  = objectMapper.writeValueAsString(body);
            String responseJson = client.post()
                .uri("/Consent")
                .bodyValue(requestJson)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            Map<?, ?> resp = objectMapper.readValue(responseJson, Map.class);
            String handle  = (String) resp.get("ConsentHandle");

            log.info("AA consent created. Handle: {}", handle);

            return AAConsentResponse.builder()
                .consentHandle(handle)
                .redirectUrl(buildAARedirectUrl(handle, mobileNumber))
                .status("PENDING")
                .build();

        } catch (Exception e) {
            log.error("AA consent creation failed: {}", e.getMessage());
            // Return mock for sandbox/demo when AA not configured
            return buildMockConsentResponse();
        }
    }

    // ─── STEP 2: Check Consent Status ────────────────────────────────────────

    public String checkConsentStatus(String consentHandle) {
        try {
            WebClient client = buildClient();
            String responseJson = client.get()
                .uri("/Consent/" + consentHandle)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            Map<?, ?> resp   = objectMapper.readValue(responseJson, Map.class);
            Map<?, ?> detail = (Map<?, ?>) resp.get("ConsentStatus");
            return detail != null ? (String) detail.get("status") : "PENDING";

        } catch (Exception e) {
            log.error("Consent status check failed: {}", e.getMessage());
            return "PENDING";
        }
    }

    // ─── STEP 3: Fetch Financial Information ─────────────────────────────────

    /**
     * Once consent is APPROVED, fetch the actual transaction data.
     * ReBIT: POST /FI/request → session ID → GET /FI/fetch/{sessionId}
     */
    public List<FetchedTransaction> fetchTransactions(LinkedAccount account,
                                                       LocalDate from, LocalDate to) {
        try {
            WebClient client = buildClient();

            // Create FI data session
            String sessionId = createFISession(client, account.getAaConsentId(), from, to);
            if (sessionId == null) return buildMockTransactions(account);

            // Poll for data (real impl: webhook or polling)
            Thread.sleep(2000);

            String fiDataJson = client.get()
                .uri("/FI/fetch/" + sessionId)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            return parseFIData(fiDataJson);

        } catch (Exception e) {
            log.warn("AA FI fetch failed ({}), returning mock data for demo.", e.getMessage());
            return buildMockTransactions(account);
        }
    }

    // ─── HELPERS ─────────────────────────────────────────────────────────────

    private String createFISession(WebClient client, String consentId,
                                   LocalDate from, LocalDate to) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ver", "2.0.0");
        body.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
        body.put("txnid", UUID.randomUUID().toString());
        body.put("FIDataRange", Map.of(
            "from", from.format(DateTimeFormatter.ISO_DATE),
            "to",   to.format(DateTimeFormatter.ISO_DATE)
        ));
        body.put("Consent", Map.of("id", consentId, "digitalSignature", ""));
        body.put("KeyMaterial", buildKeyMaterial());

        String resp = client.post()
            .uri("/FI/request")
            .bodyValue(objectMapper.writeValueAsString(body))
            .retrieve()
            .bodyToMono(String.class)
            .block();

        Map<?, ?> respMap = objectMapper.readValue(resp, Map.class);
        return (String) respMap.get("sessionId");
    }

    private Map<String, Object> buildKeyMaterial() {
        // In production: generate ECDH key pair, share public key here
        // The AA encrypts data with it; you decrypt with private key
        return Map.of(
            "cryptoAlg", "ECDH",
            "curve",     "Curve25519",
            "params",    "cipher=AES/GCM/NoPadding;KeyPairGenerator=ECDH",
            "DHPublicKey", Map.of(
                "expiry",     LocalDateTime.now().plusDays(1).format(DateTimeFormatter.ISO_DATE_TIME),
                "Parameters", "curve=curve25519",
                "KeyValue",   "BASE64_ENCODED_PUBLIC_KEY_HERE"
            ),
            "Nonce", UUID.randomUUID().toString()
        );
    }

    @SuppressWarnings("unchecked")
    private List<FetchedTransaction> parseFIData(String json) throws Exception {
        List<FetchedTransaction> results = new ArrayList<>();
        Map<?, ?> root = objectMapper.readValue(json, Map.class);
        List<?> fiObjects = (List<?>) root.get("FI");
        if (fiObjects == null) return results;

        for (Object fiObj : fiObjects) {
            Map<?, ?> fi   = (Map<?, ?>) fiObj;
            Map<?, ?> data = (Map<?, ?>) fi.get("data");
            if (data == null) continue;

            // Decode + decrypt (production: use ECDH private key here)
            // For now parse assuming plain JSON structure
            List<?> txns = (List<?>) ((Map<?, ?>) data.get("Account")).get("Transactions");
            if (txns == null) continue;

            for (Object t : txns) {
                Map<?, ?> txn = (Map<?, ?>) t;
                results.add(FetchedTransaction.builder()
                    .refId((String) txn.get("txnId"))
                    .amount(Double.parseDouble(txn.get("amount").toString()))
                    .type(txn.get("type").toString().equalsIgnoreCase("CREDIT") ? "income" : "expense")
                    .description((String) txn.get("narration"))
                    .date(LocalDate.parse(txn.get("valueDate").toString()))
                    .merchantName((String) txn.get("merchant"))
                    .build());
            }
        }
        return results;
    }

    private WebClient buildClient() {
        return WebClient.builder()
            .baseUrl(aaBaseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("client_id",     clientId)
            .defaultHeader("client_secret", clientSecret)
            .build();
    }

    private String buildAARedirectUrl(String handle, String mobile) {
        return aaBaseUrl.replace("/consentapi", "")
            + "/consent-ui?handle=" + handle
            + "&mobile=" + mobile
            + "&redirect=" + redirectUrl;
    }

    // ─── MOCK DATA (demo / sandbox fallback) ─────────────────────────────────

    private AAConsentResponse buildMockConsentResponse() {
        String handle = "MOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return AAConsentResponse.builder()
            .consentHandle(handle)
            .redirectUrl("http://localhost:8080/api/bank/aa-callback?handle=" + handle + "&approved=true")
            .status("PENDING")
            .mock(true)
            .build();
    }

    public List<FetchedTransaction> buildMockTransactions(LinkedAccount account) {
        String bank = account.getBankName();
        LocalDate now = LocalDate.now();
        return List.of(
            txn("SAL001", 85000, "income",  "SALARY CREDIT " + bank,             now.withDayOfMonth(1), "Employer"),
            txn("UPI001", 2400,  "expense", "UPI/Swiggy/Food Order",              now.minusDays(1),  "Swiggy"),
            txn("UPI002", 850,   "expense", "UPI/PhonePe/Ola Cabs",              now.minusDays(2),  "Ola"),
            txn("UPI003", 1299,  "expense", "UPI/Netflix Subscription",          now.minusDays(3),  "Netflix"),
            txn("UPI004", 3500,  "expense", "UPI/BigBasket/Groceries",           now.minusDays(4),  "BigBasket"),
            txn("ATM001", 5000,  "expense", "ATM WDL " + bank + " BRANCH",       now.minusDays(5),  null),
            txn("UPI005", 999,   "expense", "UPI/Spotify/Subscription",          now.minusDays(6),  "Spotify"),
            txn("UPI006", 1800,  "expense", "UPI/Zomato/Food Order",             now.minusDays(7),  "Zomato"),
            txn("UPI007", 12000, "expense", "NEFT/HDFC BANK/Rent Payment",       now.minusDays(8),  null),
            txn("UPI008", 450,   "expense", "UPI/MakeMyTrip/Bus Ticket",         now.minusDays(9),  "MakeMyTrip"),
            txn("UPI009", 2200,  "expense", "UPI/Myntra/Online Shopping",        now.minusDays(10), "Myntra"),
            txn("UPI010", 5000,  "income",  "UPI/IMPS/Money Transfer Received",  now.minusDays(12), null),
            txn("UPI011", 320,   "expense", "UPI/Rapido/Cab Booking",            now.minusDays(14), "Rapido"),
            txn("UPI012", 890,   "expense", "UPI/BookMyShow/Movie Tickets",      now.minusDays(15), "BookMyShow")
        );
    }

    private FetchedTransaction txn(String ref, double amt, String type,
                                    String desc, LocalDate date, String merchant) {
        return FetchedTransaction.builder()
            .refId(ref).amount(amt).type(type)
            .description(desc).date(date).merchantName(merchant).build();
    }
}
