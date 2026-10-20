package com.finora.people;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Shared payments and loans (docs/superpowers/specs/2026-10-18-shared-payments-and-loans-design.md). */
@RestController
@RequestMapping("/api/v1/people")
@Validated
public class PeopleController {

    private final PeopleService people;
    private final CurrentUser currentUser;

    public PeopleController(PeopleService people, CurrentUser currentUser) {
        this.people = people;
        this.currentUser = currentUser;
    }

    /** Everyone with an open balance: positive, they owe you; negative, you owe them. */
    @GetMapping
    public ApiResponse<List<PeopleDtos.PersonDto>> peopleList() {
        return ApiResponse.ok(people.people(currentUser.id()));
    }

    /** Rows for Insights' To look at, and the shares expected in for Coming up. */
    @GetMapping("/suggestions")
    public ApiResponse<PeopleSuggestions.PeopleSuggestionsResult> peopleSuggestions() {
        return ApiResponse.ok(people.suggestions(currentUser.id()));
    }

    @GetMapping("/person")
    public ApiResponse<PeopleDtos.PersonDetailDto> peoplePerson(@RequestParam @NotBlank String key) {
        return ApiResponse.ok(people.person(currentUser.id(), key));
    }

    /** The person a transaction was with, for its details to open their page. 422 when its payee
     *  key does not name one person. */
    @GetMapping("/of-transaction/{txnId}")
    public ApiResponse<PeopleDtos.PersonRefDto> peoplePersonOf(@PathVariable UUID txnId) {
        return ApiResponse.ok(people.personOf(currentUser.id(), txnId));
    }

    @PutMapping("/person/settings")
    public ApiResponse<Void> peoplePersonSettings(@Valid @RequestBody PeopleDtos.PersonSettingsRequest request) {
        people.setPersonSettings(currentUser.id(), request);
        return ApiResponse.ok(null, "Saved");
    }

    /** Link a credit to a bill as someone's share of it. */
    @PostMapping("/shares")
    public ApiResponse<PeopleDtos.ShareDto> peopleLinkShare(@Valid @RequestBody PeopleDtos.LinkShareRequest request) {
        return ApiResponse.ok(people.link(currentUser.id(), request));
    }

    @DeleteMapping("/shares")
    public ApiResponse<Void> peopleUnlinkShare(@RequestParam UUID creditId, @RequestParam UUID billId) {
        people.unlink(currentUser.id(), creditId, billId);
        return ApiResponse.ok(null, "Removed");
    }

    @GetMapping("/bills/{billId}/shares")
    public ApiResponse<List<PeopleDtos.ShareDto>> peopleBillShares(@PathVariable UUID billId) {
        return ApiResponse.ok(people.billShares(currentUser.id(), billId));
    }

    /** Lent, paid back, borrowed or repaid -- for that payment's full amount. */
    @PostMapping("/entries")
    public ApiResponse<PeopleDtos.EntryDto> peopleAddEntry(@Valid @RequestBody PeopleDtos.EntryRequest request) {
        return ApiResponse.ok(people.addEntry(currentUser.id(), request));
    }

    @DeleteMapping("/entries/{id}")
    public ApiResponse<Void> peopleDeleteEntry(@PathVariable UUID id) {
        people.deleteEntry(currentUser.id(), id);
        return ApiResponse.ok(null, "Removed");
    }

    /** Close what a person owes: paid in cash, or let it go. */
    @PostMapping("/settle")
    public ApiResponse<Void> peopleSettle(@Valid @RequestBody PeopleDtos.SettleRequest request) {
        people.settle(currentUser.id(), request);
        return ApiResponse.ok(null, "Saved");
    }

    @PostMapping("/split-rules")
    public ApiResponse<PeopleDtos.SplitRuleDto> peopleAddSplitRule(@Valid @RequestBody PeopleDtos.SplitRuleRequest request) {
        return ApiResponse.ok(people.addSplitRule(currentUser.id(), request));
    }

    @DeleteMapping("/split-rules/{id}")
    public ApiResponse<Void> peopleRemoveSplitRule(@PathVariable UUID id) {
        people.removeSplitRule(currentUser.id(), id);
        return ApiResponse.ok(null, "Removed");
    }

    @GetMapping("/preferences")
    public ApiResponse<PeopleDtos.PreferencesDto> peoplePreferences() {
        return ApiResponse.ok(people.preferences(currentUser.id()));
    }

    @PutMapping("/preferences")
    public ApiResponse<PeopleDtos.PreferencesDto> peopleSetPreferences(@Valid @RequestBody PeopleDtos.PreferencesRequest request) {
        return ApiResponse.ok(people.setPreferences(currentUser.id(), request));
    }
}
