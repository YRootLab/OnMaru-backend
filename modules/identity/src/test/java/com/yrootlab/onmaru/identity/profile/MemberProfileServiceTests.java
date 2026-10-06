package com.yrootlab.onmaru.identity.profile;

import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberProfileServiceTests {

    private static final UUID MEMBER_ID = UUID.fromString("55200000-0000-0000-0000-000000000001");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("55200000-0000-0000-0000-000000000002");
    private static final Instant CREATED_AT = Instant.parse("2026-10-03T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-10-03T01:00:00Z");

    @Test
    void generatesAnonymousNameAndProfileIdsFromTheFixedCatalog() {
        int[] values = {0, 1, 42, 9, 4};
        var cursor = new AtomicInteger();
        var generator = new MemberProfileGenerator(bound -> values[cursor.getAndIncrement()]);

        var generated = generator.generate();

        assertThat(generated.displayName()).isEqualTo("고요한 기와 0042");
        assertThat(generated.characterId()).isEqualTo(MemberProfileCharacter.CHARACTER_10);
        assertThat(generated.backgroundId()).isEqualTo(MemberProfileBackground.BACKGROUND_05);
    }

    @Test
    void updatesOnlyPresentFieldsAndNormalizesDisplayName() {
        var store = new FakeStore(
                profile(MEMBER_ID, "고요한 마루 0001"),
                profile(OTHER_MEMBER_ID, "같은 이름"));
        var service = new MemberProfileService(store);

        var updated = service.updateActiveProfile(
                MEMBER_ID,
                new MemberProfilePatch("  새로운 이름  ", "CHARACTER_10", null),
                UPDATED_AT).orElseThrow();

        assertThat(updated.displayName()).isEqualTo(Normalizer.normalize("새로운 이름", Normalizer.Form.NFC));
        assertThat(updated.characterId()).isEqualTo(MemberProfileCharacter.CHARACTER_10);
        assertThat(updated.backgroundId()).isEqualTo(MemberProfileBackground.BACKGROUND_01);
        assertThat(store.findByMemberId(OTHER_MEMBER_ID).orElseThrow().displayName()).isEqualTo("같은 이름");
    }

    @Test
    void rejectsAnotherMembersNormalizedDisplayName() {
        var service = new MemberProfileService(new FakeStore(
                profile(MEMBER_ID, "고요한 마루 0001"),
                profile(OTHER_MEMBER_ID, "같은 이름")));

        assertThatThrownBy(() -> service.updateActiveProfile(
                MEMBER_ID,
                new MemberProfilePatch("  같은 이름  ", null, null),
                UPDATED_AT))
                .isInstanceOf(MemberProfileDuplicateException.class);
    }

    @Test
    void rejectsInvalidDisplayNamesWithTheDisplayNameField() {
        var service = new MemberProfileService(new FakeStore(profile(MEMBER_ID, "고요한 마루 0001")));

        for (String invalid : new String[]{"   ", "가", "가".repeat(21), "두\n줄", "제어\u0000문자", "두\u2028줄", "두\u2029줄"}) {
            assertThatThrownBy(() -> service.updateActiveProfile(
                    MEMBER_ID, new MemberProfilePatch(invalid, null, null), UPDATED_AT))
                    .isInstanceOf(MemberProfileInvalidException.class)
                    .extracting("field")
                    .isEqualTo("displayName");
        }
    }

    @Test
    void rejectsIdsOutsideTheFixedCatalogWithTheirField() {
        var service = new MemberProfileService(new FakeStore(profile(MEMBER_ID, "고요한 마루 0001")));

        assertThatThrownBy(() -> service.updateActiveProfile(
                MEMBER_ID, new MemberProfilePatch(null, "CHARACTER_11", null), UPDATED_AT))
                .isInstanceOf(MemberProfileInvalidException.class)
                .extracting("field")
                .isEqualTo("characterId");
        assertThatThrownBy(() -> service.updateActiveProfile(
                MEMBER_ID, new MemberProfilePatch(null, null, "BACKGROUND_00"), UPDATED_AT))
                .isInstanceOf(MemberProfileInvalidException.class)
                .extracting("field")
                .isEqualTo("backgroundId");
    }

    @Test
    void emptyBatchReturnsWithoutCallingTheStore() {
        MemberProfileStore store = new MemberProfileStore() {
            @Override
            public Optional<MemberProfile> findByMemberId(UUID memberId) {
                throw new AssertionError("store must not be called");
            }

            @Override
            public Map<UUID, MemberProfile> findByMemberIds(Set<UUID> memberIds) {
                throw new AssertionError("store must not be called");
            }

            @Override
            public boolean existsByDisplayNameExcludingMember(String displayName, UUID excludedMemberId) {
                throw new AssertionError("store must not be called");
            }

            @Override
            public Optional<MemberProfile> updateActiveProfile(
                    UUID memberId,
                    String displayName,
                    MemberProfileCharacter characterId,
                    MemberProfileBackground backgroundId,
                    Instant updatedAt) {
                throw new AssertionError("store must not be called");
            }
        };

        assertThat(new MemberProfileService(store).findByMemberIds(Set.of())).isEmpty();
    }

    private static MemberProfile profile(UUID memberId, String displayName) {
        return new MemberProfile(
                memberId,
                displayName,
                MemberProfileCharacter.CHARACTER_01,
                MemberProfileBackground.BACKGROUND_01,
                CREATED_AT,
                CREATED_AT);
    }

    private static final class FakeStore implements MemberProfileStore {

        private final Map<UUID, MemberProfile> profiles = new HashMap<>();

        private FakeStore(MemberProfile... values) {
            for (var value : values) {
                profiles.put(value.memberId(), value);
            }
        }

        @Override
        public Optional<MemberProfile> findByMemberId(UUID memberId) {
            return Optional.ofNullable(profiles.get(memberId));
        }

        @Override
        public Map<UUID, MemberProfile> findByMemberIds(Set<UUID> memberIds) {
            var found = new HashMap<UUID, MemberProfile>();
            for (var memberId : memberIds) {
                if (profiles.containsKey(memberId)) {
                    found.put(memberId, profiles.get(memberId));
                }
            }
            return Map.copyOf(found);
        }

        @Override
        public boolean existsByDisplayNameExcludingMember(String displayName, UUID excludedMemberId) {
            return profiles.values().stream().anyMatch(profile ->
                    !profile.memberId().equals(excludedMemberId) && profile.displayName().equals(displayName));
        }

        @Override
        public Optional<MemberProfile> updateActiveProfile(
                UUID memberId,
                String displayName,
                MemberProfileCharacter characterId,
                MemberProfileBackground backgroundId,
                Instant updatedAt) {
            var current = profiles.get(memberId);
            if (current == null) {
                return Optional.empty();
            }
            var updated = new MemberProfile(
                    memberId,
                    displayName == null ? current.displayName() : displayName,
                    characterId == null ? current.characterId() : characterId,
                    backgroundId == null ? current.backgroundId() : backgroundId,
                    current.createdAt(),
                    updatedAt);
            profiles.put(memberId, updated);
            return Optional.of(updated);
        }
    }
}
