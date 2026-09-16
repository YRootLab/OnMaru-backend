CREATE TABLE "identity_members" (
  "id" varchar(36) PRIMARY KEY,
  "status" varchar(32) NOT NULL,
  "created_at" timestamp NOT NULL
);

CREATE TABLE "identity_external_accounts" (
  "id" varchar(36) PRIMARY KEY,
  "member_id" varchar(36) NOT NULL,
  "provider" varchar NOT NULL,
  "issuer" varchar NOT NULL,
  "subject" varchar NOT NULL,
  "created_at" timestamp NOT NULL
);

CREATE TABLE "identity_sessions" (
  "token_hash" varchar PRIMARY KEY,
  "member_id" varchar(36) NOT NULL,
  "created_at" timestamp NOT NULL,
  "last_seen_at" timestamp NOT NULL,
  "absolute_expires_at" timestamp NOT NULL,
  "revoked_at" timestamp
);

CREATE TABLE "identity_guests" (
  "id" varchar(36) PRIMARY KEY,
  "token_hash" varchar UNIQUE NOT NULL,
  "expires_at" timestamp NOT NULL,
  "revoked_at" timestamp
);

CREATE TABLE "identity_oauth_states" (
  "state_hash" varchar PRIMARY KEY,
  "guest_id" varchar(36),
  "browser_nonce_hash" varchar NOT NULL,
  "exploration_id" varchar(36),
  "provider" varchar NOT NULL,
  "return_path" varchar NOT NULL,
  "expires_at" timestamp NOT NULL,
  "consumed_at" timestamp
);

CREATE TABLE "identity_exploration_grants" (
  "member_id" varchar(36) NOT NULL,
  "exploration_id" varchar(36) NOT NULL,
  "expires_at" timestamp NOT NULL,
  PRIMARY KEY ("member_id", "exploration_id")
);

CREATE TABLE "catalog_regions" (
  "id" varchar(36) PRIMARY KEY,
  "parent_id" varchar(36),
  "code" varchar UNIQUE NOT NULL,
  "name" varchar NOT NULL,
  "level" varchar(32) NOT NULL,
  "active" boolean NOT NULL
);

CREATE TABLE "catalog_region_source_codes" (
  "provider" varchar NOT NULL,
  "dataset" varchar NOT NULL,
  "source_code" varchar NOT NULL,
  "valid_from" date NOT NULL,
  "region_id" varchar(36) NOT NULL,
  "valid_to" date,
  PRIMARY KEY ("provider", "dataset", "source_code", "valid_from")
);

CREATE TABLE "catalog_region_boundaries" (
  "boundary_revision" varchar NOT NULL,
  "region_id" varchar(36) NOT NULL,
  "geometry" text NOT NULL,
  "source_name" varchar NOT NULL,
  "rights_note" text NOT NULL,
  "observed_at" timestamp NOT NULL,
  PRIMARY KEY ("boundary_revision", "region_id")
);

CREATE TABLE "catalog_dataset_revisions" (
  "id" varchar(36) PRIMARY KEY,
  "dataset" varchar NOT NULL,
  "status" varchar(32) NOT NULL,
  "base_revision_id" varchar(36),
  "source_observed_at" timestamp,
  "fetched_at" timestamp NOT NULL,
  "published_at" timestamp
);

CREATE TABLE "catalog_active_datasets" (
  "dataset" varchar PRIMARY KEY,
  "revision_id" varchar(36) NOT NULL,
  "activated_at" timestamp NOT NULL
);

CREATE TABLE "catalog_place_identity" (
  "id" varchar(36) PRIMARY KEY,
  "created_at" timestamp NOT NULL
);

CREATE TABLE "catalog_place_sources" (
  "id" varchar(36) PRIMARY KEY,
  "place_id" varchar(36) NOT NULL,
  "provider" varchar NOT NULL,
  "dataset" varchar NOT NULL,
  "external_id" varchar NOT NULL,
  "language" varchar NOT NULL,
  "fetched_at" timestamp NOT NULL,
  "payload_hash" varchar
);

CREATE TABLE "catalog_place_versions" (
  "revision_id" varchar(36) NOT NULL,
  "place_id" varchar(36) NOT NULL,
  "source_ref_id" varchar(36) NOT NULL,
  "region_id" varchar(36),
  "name" varchar NOT NULL,
  "category" varchar NOT NULL,
  "address" text,
  "location" text,
  "overview" text,
  "visit_review_eligible" boolean NOT NULL,
  "status" varchar(32) NOT NULL,
  "normalized_hash" varchar NOT NULL,
  PRIMARY KEY ("revision_id", "place_id")
);

CREATE TABLE "catalog_kto_korean_content_versions" (
  "revision_id" varchar(36) NOT NULL,
  "source_ref_id" varchar(36) NOT NULL,
  "contentid" varchar NOT NULL,
  "contenttypeid" varchar,
  "title" varchar NOT NULL,
  "addr1" text,
  "addr2" text,
  "zipcode" varchar,
  "areacode" varchar,
  "sigungucode" varchar,
  "cat1" varchar,
  "cat2" varchar,
  "cat3" varchar,
  "lcls_systm1" varchar,
  "lcls_systm2" varchar,
  "lcls_systm3" varchar,
  "firstimage" text,
  "firstimage2" text,
  "cpyrht_div_cd" varchar,
  "mapx" numeric,
  "mapy" numeric,
  "mlevel" varchar,
  "tel" varchar,
  "createdtime" varchar,
  "modifiedtime" varchar,
  "showflag" varchar,
  "raw_hash" varchar NOT NULL,
  PRIMARY KEY ("revision_id", "source_ref_id")
);

CREATE TABLE "catalog_kto_korean_intro_versions" (
  "revision_id" varchar(36) NOT NULL,
  "source_ref_id" varchar(36) NOT NULL,
  "heritage1" varchar,
  "heritage2" varchar,
  "heritage3" varchar,
  "infocenter" text,
  "opendate" text,
  "restdate" text,
  "expguide" text,
  "expagerange" text,
  "accomcount" text,
  "useseason" text,
  "usetime" text,
  "parking" text,
  "chkbabycarriage" text,
  "chkpet" text,
  "chkcreditcard" text,
  "raw_hash" varchar NOT NULL,
  PRIMARY KEY ("revision_id", "source_ref_id")
);

CREATE TABLE "catalog_kto_korean_info_versions" (
  "revision_id" varchar(36) NOT NULL,
  "source_ref_id" varchar(36) NOT NULL,
  "serialnum" varchar NOT NULL,
  "infoname" varchar,
  "infotext" text,
  "fldgubun" varchar,
  "raw_hash" varchar NOT NULL,
  PRIMARY KEY ("revision_id", "source_ref_id", "serialnum")
);

CREATE TABLE "catalog_place_image_versions" (
  "revision_id" varchar(36) NOT NULL,
  "place_id" varchar(36) NOT NULL,
  "position" int NOT NULL,
  "origin_img_url" text NOT NULL,
  "small_image_url" text,
  "image_name" varchar,
  "source_ref_id" varchar(36) NOT NULL,
  "rights_note" text,
  PRIMARY KEY ("revision_id", "place_id", "position")
);

CREATE TABLE "catalog_hanok_detail_versions" (
  "revision_id" varchar(36) NOT NULL,
  "place_id" varchar(36) NOT NULL,
  "type" varchar,
  "hours" text,
  "parking" text,
  "homepage" text,
  "source_ref_id" varchar(36) NOT NULL,
  PRIMARY KEY ("revision_id", "place_id")
);

CREATE TABLE "catalog_place_content_tag_versions" (
  "revision_id" varchar(36) NOT NULL,
  "place_id" varchar(36) NOT NULL,
  "position" int NOT NULL,
  "label" varchar NOT NULL,
  "score" numeric NOT NULL,
  "source" varchar(32) NOT NULL,
  "algorithm_version" varchar NOT NULL,
  "source_hash" varchar NOT NULL,
  "generated_at" timestamp NOT NULL,
  PRIMARY KEY ("revision_id", "place_id", "position")
);

CREATE TABLE "content_tag_overrides" (
  "id" varchar(36) PRIMARY KEY,
  "target_type" varchar(32) NOT NULL,
  "target_id" varchar(36) NOT NULL,
  "label" varchar NOT NULL,
  "action" varchar(32) NOT NULL,
  "reason" text,
  "created_by" varchar(36),
  "created_at" timestamp NOT NULL,
  "expires_at" timestamp
);

CREATE TABLE "audio_odii_spots" (
  "id" varchar(36) PRIMARY KEY,
  "provider" varchar NOT NULL,
  "tid" varchar NOT NULL,
  "tlid" varchar NOT NULL,
  "lang_code" varchar NOT NULL,
  "created_at" timestamp NOT NULL
);

CREATE TABLE "audio_odii_stories" (
  "id" varchar(36) PRIMARY KEY,
  "spot_id" varchar(36) NOT NULL,
  "provider" varchar NOT NULL,
  "stid" varchar NOT NULL,
  "stlid" varchar NOT NULL,
  "lang_code" varchar NOT NULL,
  "created_at" timestamp NOT NULL
);

CREATE TABLE "audio_spot_versions" (
  "revision_id" varchar(36) NOT NULL,
  "spot_id" varchar(36) NOT NULL,
  "title" varchar NOT NULL,
  "address" text,
  "location" text,
  "status" varchar(32) NOT NULL,
  "hash" varchar NOT NULL,
  "source_modified_at" timestamp,
  "observed" boolean NOT NULL DEFAULT true,
  "missing_observations" int NOT NULL DEFAULT 0,
  PRIMARY KEY ("revision_id", "spot_id")
);

CREATE TABLE "audio_story_versions" (
  "revision_id" varchar(36) NOT NULL,
  "story_id" varchar(36) NOT NULL,
  "spot_id" varchar(36) NOT NULL,
  "title" varchar NOT NULL,
  "script" text,
  "audio_url" text,
  "image_url" text,
  "duration_seconds" int,
  "status" varchar(32) NOT NULL,
  "hash" varchar NOT NULL,
  "transcript_provenance" varchar NOT NULL,
  "source_modified_at" timestamp,
  "observed" boolean NOT NULL DEFAULT true,
  "missing_observations" int NOT NULL DEFAULT 0,
  PRIMARY KEY ("revision_id", "story_id")
);

CREATE TABLE "audio_revision_stages" (
  "revision_id" varchar(36) PRIMARY KEY,
  "ready" boolean NOT NULL DEFAULT false,
  "row_count" bigint NOT NULL DEFAULT 0,
  "empty_full_sync_reviewed" boolean NOT NULL DEFAULT false,
  "failure_code" varchar,
  "tombstone_count" bigint NOT NULL DEFAULT 0
);

CREATE TABLE "audio_subtitle_lines" (
  "revision_id" varchar(36) NOT NULL,
  "story_id" varchar(36) NOT NULL,
  "position" int NOT NULL,
  "text" text NOT NULL,
  "start_seconds" numeric,
  "timing_mode" varchar NOT NULL,
  PRIMARY KEY ("revision_id", "story_id", "position")
);

CREATE TABLE "audio_place_odii_links" (
  "place_id" varchar(36) NOT NULL,
  "spot_id" varchar(36) NOT NULL,
  "match_method" varchar NOT NULL,
  "confidence" numeric,
  "review_status" varchar NOT NULL DEFAULT 'APPROVED',
  "verified_at" timestamp,
  PRIMARY KEY ("place_id", "spot_id")
);

CREATE TABLE "audio_story_content_tag_versions" (
  "revision_id" varchar(36) NOT NULL,
  "story_id" varchar(36) NOT NULL,
  "position" int NOT NULL,
  "label" varchar NOT NULL,
  "score" numeric NOT NULL,
  "source" varchar(32) NOT NULL,
  "algorithm_version" varchar NOT NULL,
  "source_hash" varchar NOT NULL,
  "generated_at" timestamp NOT NULL,
  PRIMARY KEY ("revision_id", "story_id", "position")
);

CREATE TABLE "insights_visitor_observations" (
  "revision_id" varchar(36) NOT NULL,
  "region_id" varchar(36) NOT NULL,
  "basis_date" date NOT NULL,
  "visitor_type" varchar NOT NULL,
  "provider" varchar NOT NULL,
  "spatial_level" varchar NOT NULL,
  "visitor_count" bigint NOT NULL,
  "source_observed_at" timestamp,
  "fetched_at" timestamp NOT NULL,
  PRIMARY KEY ("revision_id", "region_id", "basis_date", "visitor_type")
);

CREATE TABLE "insights_tourism_targets" (
  "id" varchar(36) PRIMARY KEY,
  "provider" varchar NOT NULL,
  "source_target_key" varchar NOT NULL,
  "region_id" varchar(36) NOT NULL,
  "source_name" varchar NOT NULL
);

CREATE TABLE "insights_target_place_links" (
  "target_id" varchar(36) PRIMARY KEY,
  "place_id" varchar(36) NOT NULL,
  "match_method" varchar NOT NULL,
  "verified_at" timestamp
);

CREATE TABLE "insights_concentration_observations" (
  "revision_id" varchar(36) NOT NULL,
  "target_id" varchar(36) NOT NULL,
  "basis_date" date NOT NULL,
  "metric_type" varchar NOT NULL,
  "value" numeric NOT NULL,
  "unit" varchar NOT NULL,
  "source_observed_at" timestamp,
  "fetched_at" timestamp NOT NULL,
  PRIMARY KEY ("revision_id", "target_id", "basis_date", "metric_type")
);

CREATE TABLE "discovery_explorations" (
  "id" varchar(36) PRIMARY KEY,
  "owner_member_id" varchar(36),
  "owner_guest_id" varchar(36),
  "state_version" int NOT NULL,
  "board" text,
  "pinned_refs" text NOT NULL,
  "excluded_refs" text NOT NULL,
  "region_id" varchar(36),
  "expires_at" timestamp,
  "deleted_at" timestamp,
  "created_at" timestamp NOT NULL,
  "updated_at" timestamp NOT NULL
);

CREATE TABLE "discovery_runs" (
  "id" varchar(36) PRIMARY KEY,
  "exploration_id" varchar(36) NOT NULL,
  "actor_key" varchar NOT NULL,
  "base_version" int NOT NULL,
  "status" varchar(32) NOT NULL,
  "stage" varchar,
  "outcome" varchar,
  "clarification" text,
  "created_at" timestamp NOT NULL,
  "deadline_at" timestamp NOT NULL,
  "started_at" timestamp,
  "generation" int NOT NULL,
  "error_code" varchar,
  "engine" varchar NOT NULL
);

CREATE TABLE "discovery_run_commands" (
  "actor_key" varchar NOT NULL,
  "operation" varchar NOT NULL,
  "command_key" varchar(36) NOT NULL,
  "request_hash" varchar NOT NULL,
  "run_id" varchar(36) NOT NULL,
  "result_status" varchar(32) NOT NULL,
  "result_stage" varchar,
  "result_outcome" varchar,
  "result_generation" int NOT NULL,
  "created_at" timestamp NOT NULL,
  "expires_at" timestamp NOT NULL,
  PRIMARY KEY ("actor_key", "operation", "command_key")
);

CREATE TABLE "discovery_proposals" (
  "id" varchar(36) PRIMARY KEY,
  "run_id" varchar(36) UNIQUE NOT NULL,
  "exploration_id" varchar(36) NOT NULL,
  "base_version" int NOT NULL,
  "ordered_refs" text NOT NULL,
  "reasons" text NOT NULL,
  "evidence" text NOT NULL,
  "expires_at" timestamp NOT NULL,
  "status" varchar(32) NOT NULL
);

CREATE TABLE "discovery_turns" (
  "id" varchar(36) PRIMARY KEY,
  "exploration_id" varchar(36) NOT NULL,
  "client_turn_id" varchar(36) NOT NULL,
  "query" text NOT NULL,
  "created_at" timestamp NOT NULL
);

CREATE TABLE "journey_saved_journeys" (
  "id" varchar(36) PRIMARY KEY,
  "member_id" varchar(36) NOT NULL,
  "source_exploration_id" varchar(36),
  "source_version" int NOT NULL,
  "saved_at" timestamp NOT NULL,
  "title" varchar NOT NULL,
  "snapshot" text NOT NULL,
  "snapshot_hash" varchar NOT NULL
);

CREATE TABLE "journey_saved_resources" (
  "id" varchar(36) PRIMARY KEY,
  "member_id" varchar(36) NOT NULL,
  "resource_type" varchar(32) NOT NULL,
  "resource_id" varchar(36) NOT NULL,
  "saved_at" timestamp NOT NULL
);

CREATE TABLE "community_visit_reviews" (
  "id" varchar(36) PRIMARY KEY,
  "member_id" varchar(36) NOT NULL,
  "place_id" varchar(36) NOT NULL,
  "text" text NOT NULL,
  "status" varchar(32) NOT NULL,
  "created_at" timestamp NOT NULL,
  "deleted_at" timestamp
);

CREATE TABLE "community_review_likes" (
  "review_id" varchar(36) NOT NULL,
  "member_id" varchar(36) NOT NULL,
  "created_at" timestamp NOT NULL,
  PRIMARY KEY ("review_id", "member_id")
);

CREATE TABLE "community_review_reports" (
  "id" varchar(36) PRIMARY KEY,
  "review_id" varchar(36) NOT NULL,
  "reporter_member_id" varchar(36) NOT NULL,
  "reason" varchar NOT NULL,
  "detail" text,
  "status" varchar(32) NOT NULL,
  "created_at" timestamp NOT NULL,
  "resolved_at" timestamp
);

CREATE TABLE "community_review_moderation_actions" (
  "id" varchar(36) PRIMARY KEY,
  "review_id" varchar(36) NOT NULL,
  "actor_type" varchar NOT NULL,
  "actor_ref" varchar,
  "previous_status" varchar(32) NOT NULL,
  "next_status" varchar(32) NOT NULL,
  "reason" varchar NOT NULL,
  "created_at" timestamp NOT NULL
);

CREATE TABLE "operations_idempotency" (
  "actor_key" varchar NOT NULL,
  "operation" varchar NOT NULL,
  "key" varchar(36) NOT NULL,
  "request_hash" varchar NOT NULL,
  "response_status" int NOT NULL,
  "response_body" text,
  "expires_at" timestamp NOT NULL,
  PRIMARY KEY ("actor_key", "operation", "key")
);

CREATE TABLE "operations_admission" (
  "scope_key" varchar PRIMARY KEY,
  "window_start" timestamp NOT NULL,
  "consumed" int NOT NULL,
  "active_count" int NOT NULL
);

CREATE TABLE "operations_admission_audit" (
  "id" varchar(36) PRIMARY KEY,
  "scope_key" varchar NOT NULL,
  "operation" varchar NOT NULL,
  "subject_type" varchar NOT NULL,
  "window_start" timestamp NOT NULL,
  "decision" varchar NOT NULL,
  "reason" varchar,
  "limit_value" int NOT NULL,
  "consumed_after" int NOT NULL,
  "active_after" int NOT NULL,
  "retry_after_ms" bigint NOT NULL,
  "occurred_at" timestamp NOT NULL
);

CREATE TABLE "operations_sync_schedules" (
  "dataset" varchar PRIMARY KEY,
  "timezone" varchar NOT NULL,
  "local_time" varchar NOT NULL,
  "enabled" boolean NOT NULL,
  "next_due_at" timestamp NOT NULL
);

CREATE TABLE "operations_sync_runs" (
  "id" varchar(36) PRIMARY KEY,
  "dataset" varchar NOT NULL,
  "scheduled_for" timestamp NOT NULL,
  "attempt" int NOT NULL,
  "status" varchar(32) NOT NULL,
  "revision_id" varchar(36),
  "started_at" timestamp,
  "finished_at" timestamp,
  "next_attempt_at" timestamp,
  "error_code" varchar,
  "counts" text
);

CREATE TABLE "operations_sync_checkpoints" (
  "run_id" varchar(36) NOT NULL,
  "partition_key" varchar NOT NULL,
  "next_page" int,
  "source_cursor" varchar,
  "expected_total" int,
  "seen_count" int NOT NULL,
  "last_source_modified" varchar,
  "last_external_id" varchar,
  PRIMARY KEY ("run_id", "partition_key")
);

CREATE TABLE "operations_sync_leases" (
  "dataset" varchar PRIMARY KEY,
  "owner_token" varchar NOT NULL,
  "generation" int NOT NULL,
  "lease_until" timestamp NOT NULL
);

CREATE TABLE "operations_sync_watermarks" (
  "dataset" varchar PRIMARY KEY,
  "source_modified_at" varchar,
  "external_id" varchar,
  "last_full_success_at" timestamp,
  "last_success_at" timestamp,
  "revision_id" varchar(36) NOT NULL
);

CREATE TABLE "operations_sync_quarantine" (
  "run_id" varchar(36) NOT NULL,
  "record_key" varchar NOT NULL,
  "error_code" varchar NOT NULL,
  "payload_hash" varchar NOT NULL,
  "redacted_payload" text,
  "expires_at" timestamp NOT NULL,
  PRIMARY KEY ("run_id", "record_key")
);

CREATE TABLE "ai_documents" (
  "document_id" varchar(36) NOT NULL,
  "revision" varchar NOT NULL,
  "content_hash" varchar NOT NULL,
  "active" boolean NOT NULL,
  "source_type" varchar NOT NULL,
  "source_ref" varchar NOT NULL,
  "metadata" text,
  PRIMARY KEY ("document_id", "revision")
);

CREATE TABLE "ai_chunks" (
  "chunk_id" varchar(36) PRIMARY KEY,
  "document_id" varchar(36) NOT NULL,
  "revision" varchar NOT NULL,
  "position" int NOT NULL,
  "text" text NOT NULL,
  "offsets" text
);

CREATE TABLE "ai_embeddings" (
  "chunk_id" varchar(36) NOT NULL,
  "embedding_profile_id" varchar NOT NULL,
  "vector" text,
  PRIMARY KEY ("chunk_id", "embedding_profile_id")
);

CREATE TABLE "ai_corpus_sync_runs" (
  "id" varchar(36) PRIMARY KEY,
  "source_revision" varchar NOT NULL,
  "source_manifest_hash" varchar NOT NULL,
  "status" varchar NOT NULL,
  "started_at" timestamp NOT NULL,
  "finished_at" timestamp,
  "error_code" varchar
);





































































COMMENT ON TABLE "discovery_explorations" IS 'Executable DDL must enforce exactly one owner: (owner_member_id IS NULL) <> (owner_guest_id IS NULL).';

COMMENT ON TABLE "discovery_runs" IS 'Executable DDL must enforce status/stage/outcome compatibility and partial unique indexes: one QUEUED or RUNNING run per exploration and per actor_key.';

COMMENT ON TABLE "discovery_run_commands" IS 'Durable command receipt. The executable migration enforces the operation/stage allowlists, positive generation, expiry, and composite primary key.';







ALTER TABLE "identity_oauth_states" ADD FOREIGN KEY ("exploration_id") REFERENCES "discovery_explorations" ("id");

ALTER TABLE "identity_exploration_grants" ADD FOREIGN KEY ("exploration_id") REFERENCES "discovery_explorations" ("id");

ALTER TABLE "discovery_explorations" ADD FOREIGN KEY ("owner_member_id") REFERENCES "identity_members" ("id");

ALTER TABLE "discovery_explorations" ADD FOREIGN KEY ("owner_guest_id") REFERENCES "identity_guests" ("id");

ALTER TABLE "discovery_explorations" ADD FOREIGN KEY ("region_id") REFERENCES "catalog_regions" ("id");

ALTER TABLE "journey_saved_journeys" ADD FOREIGN KEY ("member_id") REFERENCES "identity_members" ("id");

ALTER TABLE "journey_saved_journeys" ADD FOREIGN KEY ("source_exploration_id") REFERENCES "discovery_explorations" ("id");

ALTER TABLE "community_visit_reviews" ADD FOREIGN KEY ("member_id") REFERENCES "identity_members" ("id");

ALTER TABLE "community_visit_reviews" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id");

ALTER TABLE "community_review_likes" ADD FOREIGN KEY ("member_id") REFERENCES "identity_members" ("id");

ALTER TABLE "community_review_reports" ADD FOREIGN KEY ("reporter_member_id") REFERENCES "identity_members" ("id");

ALTER TABLE "audio_place_odii_links" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id");

ALTER TABLE "audio_revision_stages" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id");

ALTER TABLE "insights_visitor_observations" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id");

ALTER TABLE "insights_visitor_observations" ADD FOREIGN KEY ("region_id") REFERENCES "catalog_regions" ("id");

ALTER TABLE "insights_tourism_targets" ADD FOREIGN KEY ("region_id") REFERENCES "catalog_regions" ("id");

ALTER TABLE "insights_target_place_links" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id");

ALTER TABLE "insights_concentration_observations" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id");

ALTER TABLE "insights_concentration_observations" ADD FOREIGN KEY ("target_id") REFERENCES "insights_tourism_targets" ("id");

ALTER TABLE "operations_sync_runs" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id");

ALTER TABLE "operations_sync_watermarks" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id");

ALTER TABLE "identity_external_accounts" ADD FOREIGN KEY ("member_id") REFERENCES "identity_members" ("id");

ALTER TABLE "identity_sessions" ADD FOREIGN KEY ("member_id") REFERENCES "identity_members" ("id");

ALTER TABLE "identity_oauth_states" ADD FOREIGN KEY ("guest_id") REFERENCES "identity_guests" ("id");

ALTER TABLE "identity_exploration_grants" ADD FOREIGN KEY ("member_id") REFERENCES "identity_members" ("id");

ALTER TABLE "catalog_regions" ADD FOREIGN KEY ("parent_id") REFERENCES "catalog_regions" ("id");

ALTER TABLE "catalog_region_source_codes" ADD FOREIGN KEY ("region_id") REFERENCES "catalog_regions" ("id");

ALTER TABLE "catalog_region_boundaries" ADD FOREIGN KEY ("region_id") REFERENCES "catalog_regions" ("id");

ALTER TABLE "catalog_dataset_revisions" ADD FOREIGN KEY ("base_revision_id") REFERENCES "catalog_dataset_revisions" ("id");

ALTER TABLE "catalog_active_datasets" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id");

ALTER TABLE "catalog_place_sources" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id");

ALTER TABLE "catalog_place_versions" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id");

ALTER TABLE "catalog_place_versions" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id");

ALTER TABLE "catalog_place_versions" ADD FOREIGN KEY ("source_ref_id") REFERENCES "catalog_place_sources" ("id");

ALTER TABLE "catalog_place_versions" ADD FOREIGN KEY ("region_id") REFERENCES "catalog_regions" ("id");

ALTER TABLE "catalog_kto_korean_content_versions" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id");

ALTER TABLE "catalog_kto_korean_content_versions" ADD FOREIGN KEY ("source_ref_id") REFERENCES "catalog_place_sources" ("id");

ALTER TABLE "catalog_kto_korean_intro_versions" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id");

ALTER TABLE "catalog_kto_korean_intro_versions" ADD FOREIGN KEY ("source_ref_id") REFERENCES "catalog_place_sources" ("id");

ALTER TABLE "catalog_kto_korean_info_versions" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id");

ALTER TABLE "catalog_kto_korean_info_versions" ADD FOREIGN KEY ("source_ref_id") REFERENCES "catalog_place_sources" ("id");

ALTER TABLE "catalog_place_image_versions" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id");

ALTER TABLE "catalog_place_image_versions" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id");

ALTER TABLE "catalog_place_image_versions" ADD FOREIGN KEY ("source_ref_id") REFERENCES "catalog_place_sources" ("id");

ALTER TABLE "catalog_hanok_detail_versions" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id");

ALTER TABLE "catalog_hanok_detail_versions" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id");

ALTER TABLE "catalog_hanok_detail_versions" ADD FOREIGN KEY ("source_ref_id") REFERENCES "catalog_place_sources" ("id");

ALTER TABLE "catalog_place_content_tag_versions" ADD FOREIGN KEY ("revision_id", "place_id") REFERENCES "catalog_place_versions" ("revision_id", "place_id");

ALTER TABLE "audio_odii_stories" ADD FOREIGN KEY ("spot_id") REFERENCES "audio_odii_spots" ("id");

ALTER TABLE "audio_spot_versions" ADD FOREIGN KEY ("spot_id") REFERENCES "audio_odii_spots" ("id");

ALTER TABLE "audio_story_versions" ADD FOREIGN KEY ("story_id") REFERENCES "audio_odii_stories" ("id");

ALTER TABLE "audio_story_versions" ADD FOREIGN KEY ("spot_id") REFERENCES "audio_odii_spots" ("id");

ALTER TABLE "audio_subtitle_lines" ADD FOREIGN KEY ("story_id") REFERENCES "audio_odii_stories" ("id");

ALTER TABLE "audio_place_odii_links" ADD FOREIGN KEY ("spot_id") REFERENCES "audio_odii_spots" ("id");

ALTER TABLE "audio_story_content_tag_versions" ADD FOREIGN KEY ("revision_id", "story_id") REFERENCES "audio_story_versions" ("revision_id", "story_id");

ALTER TABLE "insights_target_place_links" ADD FOREIGN KEY ("target_id") REFERENCES "insights_tourism_targets" ("id");

ALTER TABLE "discovery_runs" ADD FOREIGN KEY ("exploration_id") REFERENCES "discovery_explorations" ("id");

ALTER TABLE "discovery_run_commands" ADD FOREIGN KEY ("run_id") REFERENCES "discovery_runs" ("id");

ALTER TABLE "discovery_proposals" ADD FOREIGN KEY ("run_id") REFERENCES "discovery_runs" ("id");

ALTER TABLE "discovery_proposals" ADD FOREIGN KEY ("exploration_id") REFERENCES "discovery_explorations" ("id");

ALTER TABLE "discovery_turns" ADD FOREIGN KEY ("exploration_id") REFERENCES "discovery_explorations" ("id");

ALTER TABLE "community_review_likes" ADD FOREIGN KEY ("review_id") REFERENCES "community_visit_reviews" ("id");

ALTER TABLE "community_review_reports" ADD FOREIGN KEY ("review_id") REFERENCES "community_visit_reviews" ("id");

ALTER TABLE "community_review_moderation_actions" ADD FOREIGN KEY ("review_id") REFERENCES "community_visit_reviews" ("id");

ALTER TABLE "operations_sync_checkpoints" ADD FOREIGN KEY ("run_id") REFERENCES "operations_sync_runs" ("id");

ALTER TABLE "operations_sync_quarantine" ADD FOREIGN KEY ("run_id") REFERENCES "operations_sync_runs" ("id");

ALTER TABLE "ai_chunks" ADD FOREIGN KEY ("document_id", "revision") REFERENCES "ai_documents" ("document_id", "revision");

ALTER TABLE "ai_embeddings" ADD FOREIGN KEY ("chunk_id") REFERENCES "ai_chunks" ("chunk_id");
