package com.finn.controller;

import com.finn.dto.*;
import com.finn.service.BankLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bank")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BankController {

    private final BankLinkService bankLinkService;

    /** Link a new bank account + initiate AA consent */
    @PostMapping("/link")
    public ResponseEntity<BankLinkResponse> linkAccount(@RequestBody BankLinkRequest req) {
        return ResponseEntity.ok(bankLinkService.linkAccount(req));
    }

    /** AA callback after user approves/rejects consent */
    @GetMapping("/aa-callback")
    public ResponseEntity<SyncResponse> aaCallback(
        @RequestParam String handle,
        @RequestParam(defaultValue = "false") boolean approved) {
        return ResponseEntity.ok(bankLinkService.handleAACallback(handle, approved));
    }

    /** Manually trigger a sync */
    @PostMapping("/sync/{accountId}")
    public ResponseEntity<SyncResponse> sync(@PathVariable Long accountId) {
        return ResponseEntity.ok(bankLinkService.syncAccount(accountId));
    }

    /** List all linked accounts for a user */
    @GetMapping("/accounts/{userId}")
    public ResponseEntity<List<LinkedAccountResponse>> accounts(@PathVariable Long userId) {
        return ResponseEntity.ok(bankLinkService.getLinkedAccounts(userId));
    }
}
