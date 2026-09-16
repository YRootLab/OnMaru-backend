CREATE TYPE "identity_member_status" AS ENUM (
  'ACTIVE',
  'DELETING'
);

CREATE TYPE "catalog_region_level" AS ENUM (
  'SIDO',
  'SIGUNGU'
);

CREATE TYPE "catalog_dataset_status" AS ENUM (
  'STAGING',
  'READY',
  'PUBLISHED',
  'FAILED'
);

CREATE TYPE "catalog_place_status" AS ENUM (
  'ACTIVE',
  'HIDDEN',
  'DELETED'
);

CREATE TYPE "audio_status" AS ENUM (
  'ACTIVE',
  'HIDDEN',
  'DELETED'
);

CREATE TYPE "discovery_run_status" AS ENUM (
  'QUEUED',
  'RUNNING',
  'COMPLETED',
  'FAILED',
  'CANCELLED'
);

CREATE TYPE "discovery_proposal_status" AS ENUM (
  'PENDING',
  'APPLIED',
  'DISMISSED',
  'INVALIDATED'
);

CREATE TYPE "journey_saved_resource_type" AS ENUM (
  'PLACE',
  'ODII_STORY'
);

CREATE TYPE "community_review_status" AS ENUM (
  'PUBLISHED',
  'HIDDEN',
  'REMOVED',
  'DELETED'
);

CREATE TYPE "community_report_status" AS ENUM (
  'OPEN',
  'RESOLVED',
  'DISMISSED'
);

CREATE TYPE "operations_sync_run_status" AS ENUM (
  'QUEUED',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
  'ABANDONED'
);

CREATE TABLE "identity_members" (
  "id" uuid PRIMARY KEY,
  "status" identity_member_status NOT NULL,
  "created_at" timestamptz NOT NULL
);

CREATE TABLE "identity_external_accounts" (
  "id" uuid PRIMARY KEY,
  "member_id" uuid NOT NULL,
  "provider" varchar NOT NULL,
  "issuer" varchar NOT NULL,
  "subject" varchar NOT NULL,
  "created_at" timestamptz NOT NULL
);

CREATE TABLE "identity_sessions" (
  "token_hash" varchar PRIMARY KEY,
  "member_id" uuid NOT NULL,
  "created_at" timestamptz NOT NULL,
  "last_seen_at" timestamptz NOT NULL,
  "absolute_expires_at" timestamptz NOT NULL,
  "revoked_at" timestamptz
);

CREATE TABLE "identity_guests" (
  "id" uuid PRIMARY KEY,
  "token_hash" varchar UNIQUE NOT NULL,
  "expires_at" timestamptz NOT NULL,
  "revoked_at" timestamptz
);

CREATE TABLE "identity_oauth_states" (
  "state_hash" varchar PRIMARY KEY,
  "guest_id" uuid,
  "browser_nonce_hash" varchar NOT NULL,
  "exploration_id" uuid,
  "provider" varchar NOT NULL,
  "return_path" varchar NOT NULL,
  "expires_at" timestamptz NOT NULL,
  "consumed_at" timestamptz
);

CREATE TABLE "identity_exploration_grants" (
  "member_id" uuid NOT NULL,
  "exploration_id" uuid NOT NULL,
  "expires_at" timestamptz NOT NULL,
  PRIMARY KEY ("member_id", "exploration_id")
);

CREATE TABLE "catalog_regions" (
  "id" uuid PRIMARY KEY,
  "parent_id" uuid,
  "code" varchar UNIQUE NOT NULL,
  "name" varchar NOT NULL,
  "level" catalog_region_level NOT NULL,
  "active" boolean NOT NULL
);

CREATE TABLE "catalog_region_source_codes" (
  "provider" varchar NOT NULL,
  "dataset" varchar NOT NULL,
  "source_code" varchar NOT NULL,
  "valid_from" date NOT NULL,
  "region_id" uuid NOT NULL,
  "valid_to" date,
  PRIMARY KEY ("provider", "dataset", "source_code", "valid_from")
);

CREATE TABLE "catalog_region_boundaries" (
  "boundary_revision" varchar NOT NULL,
  "region_id" uuid NOT NULL,
  "geometry" geometry NOT NULL,
  "source_name" varchar NOT NULL,
  "rights_note" text NOT NULL,
  "observed_at" timestamptz NOT NULL,
  PRIMARY KEY ("boundary_revision", "region_id")
);

CREATE TABLE "catalog_dataset_revisions" (
  "id" uuid PRIMARY KEY,
  "dataset" varchar NOT NULL,
  "status" catalog_dataset_status NOT NULL,
  "base_revision_id" uuid,
  "source_observed_at" timestamptz,
  "fetched_at" timestamptz NOT NULL,
  "published_at" timestamptz
);

CREATE TABLE "catalog_active_datasets" (
  "dataset" varchar PRIMARY KEY,
  "revision_id" uuid NOT NULL,
  "activated_at" timestamptz NOT NULL
);

CREATE TABLE "catalog_place_identity" (
  "id" uuid PRIMARY KEY,
  "created_at" timestamptz NOT NULL
);

CREATE TABLE "catalog_place_sources" (
  "id" uuid PRIMARY KEY,
  "place_id" uuid NOT NULL,
  "provider" varchar NOT NULL,
  "dataset" varchar NOT NULL,
  "external_id" varchar NOT NULL,
  "language" varchar NOT NULL,
  "fetched_at" timestamptz NOT NULL,
  "payload_hash" varchar
);

CREATE TABLE "catalog_place_versions" (
  "revision_id" uuid NOT NULL,
  "place_id" uuid NOT NULL,
  "source_ref_id" uuid NOT NULL,
  "region_id" uuid,
  "name" varchar NOT NULL,
  "category" varchar NOT NULL,
  "address" text,
  "location" geography,
  "overview" text,
  "visit_review_eligible" boolean NOT NULL,
  "status" catalog_place_status NOT NULL,
  "normalized_hash" varchar NOT NULL,
  PRIMARY KEY ("revision_id", "place_id")
);

CREATE TABLE "catalog_kto_korean_content_versions" (
  "revision_id" uuid NOT NULL,
  "source_ref_id" uuid NOT NULL,
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
  "revision_id" uuid NOT NULL,
  "source_ref_id" uuid NOT NULL,
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
  "revision_id" uuid NOT NULL,
  "source_ref_id" uuid NOT NULL,
  "serialnum" varchar NOT NULL,
  "infoname" varchar,
  "infotext" text,
  "fldgubun" varchar,
  "raw_hash" varchar NOT NULL,
  PRIMARY KEY ("revision_id", "source_ref_id", "serialnum")
);

CREATE TABLE "catalog_place_image_versions" (
  "revision_id" uuid NOT NULL,
  "place_id" uuid NOT NULL,
  "position" int NOT NULL,
  "origin_img_url" text NOT NULL,
  "small_image_url" text,
  "image_name" varchar,
  "source_ref_id" uuid NOT NULL,
  "rights_note" text,
  PRIMARY KEY ("revision_id", "place_id", "position")
);

CREATE TABLE "catalog_hanok_detail_versions" (
  "revision_id" uuid NOT NULL,
  "place_id" uuid NOT NULL,
  "type" varchar,
  "hours" text,
  "parking" text,
  "homepage" text,
  "source_ref_id" uuid NOT NULL,
  PRIMARY KEY ("revision_id", "place_id")
);

CREATE TABLE "audio_odii_spots" (
  "id" uuid PRIMARY KEY,
  "provider" varchar NOT NULL,
  "tid" varchar NOT NULL,
  "tlid" varchar NOT NULL,
  "lang_code" varchar NOT NULL,
  "created_at" timestamptz NOT NULL
);

CREATE TABLE "audio_odii_stories" (
  "id" uuid PRIMARY KEY,
  "spot_id" uuid NOT NULL,
  "provider" varchar NOT NULL,
  "stid" varchar NOT NULL,
  "stlid" varchar NOT NULL,
  "lang_code" varchar NOT NULL,
  "created_at" timestamptz NOT NULL
);

CREATE TABLE "audio_spot_versions" (
  "revision_id" uuid NOT NULL,
  "spot_id" uuid NOT NULL,
  "title" varchar NOT NULL,
  "address" text,
  "location" geography,
  "status" audio_status NOT NULL,
  "hash" varchar NOT NULL,
  "source_modified_at" timestamptz,
  "observed" boolean NOT NULL DEFAULT true,
  "missing_observations" int NOT NULL DEFAULT 0,
  PRIMARY KEY ("revision_id", "spot_id")
);

CREATE TABLE "audio_story_versions" (
  "revision_id" uuid NOT NULL,
  "story_id" uuid NOT NULL,
  "spot_id" uuid NOT NULL,
  "title" varchar NOT NULL,
  "script" text,
  "audio_url" text,
  "image_url" text,
  "duration_seconds" int,
  "status" audio_status NOT NULL,
  "hash" varchar NOT NULL,
  "transcript_provenance" varchar NOT NULL,
  "source_modified_at" timestamptz,
  "observed" boolean NOT NULL DEFAULT true,
  "missing_observations" int NOT NULL DEFAULT 0,
  PRIMARY KEY ("revision_id", "story_id")
);

CREATE TABLE "audio_revision_stages" (
  "revision_id" uuid PRIMARY KEY,
  "ready" boolean NOT NULL DEFAULT false,
  "row_count" bigint NOT NULL DEFAULT 0,
  "empty_full_sync_reviewed" boolean NOT NULL DEFAULT false,
  "failure_code" varchar,
  "tombstone_count" bigint NOT NULL DEFAULT 0
);

CREATE TABLE "audio_subtitle_lines" (
  "revision_id" uuid NOT NULL,
  "story_id" uuid NOT NULL,
  "position" int NOT NULL,
  "text" text NOT NULL,
  "start_seconds" numeric,
  "timing_mode" varchar NOT NULL,
  PRIMARY KEY ("revision_id", "story_id", "position")
);

CREATE TABLE "audio_place_odii_links" (
  "place_id" uuid NOT NULL,
  "spot_id" uuid NOT NULL,
  "match_method" varchar NOT NULL,
  "confidence" numeric,
  "review_status" varchar NOT NULL DEFAULT 'APPROVED',
  "verified_at" timestamptz,
  PRIMARY KEY ("place_id", "spot_id")
);

CREATE TABLE "insights_visitor_observations" (
  "revision_id" uuid NOT NULL,
  "region_id" uuid NOT NULL,
  "basis_date" date NOT NULL,
  "visitor_type" varchar NOT NULL,
  "provider" varchar NOT NULL,
  "spatial_level" varchar NOT NULL,
  "visitor_count" bigint NOT NULL,
  "source_observed_at" timestamptz,
  "fetched_at" timestamptz NOT NULL,
  PRIMARY KEY ("revision_id", "region_id", "basis_date", "visitor_type")
);

CREATE TABLE "insights_tourism_targets" (
  "id" uuid PRIMARY KEY,
  "provider" varchar NOT NULL,
  "source_target_key" varchar NOT NULL,
  "region_id" uuid NOT NULL,
  "source_name" varchar NOT NULL
);

CREATE TABLE "insights_target_place_links" (
  "target_id" uuid PRIMARY KEY,
  "place_id" uuid NOT NULL,
  "match_method" varchar NOT NULL,
  "verified_at" timestamptz
);

CREATE TABLE "insights_concentration_observations" (
  "revision_id" uuid NOT NULL,
  "target_id" uuid NOT NULL,
  "basis_date" date NOT NULL,
  "metric_type" varchar NOT NULL,
  "value" numeric NOT NULL,
  "unit" varchar NOT NULL,
  "source_observed_at" timestamptz,
  "fetched_at" timestamptz NOT NULL,
  PRIMARY KEY ("revision_id", "target_id", "basis_date", "metric_type")
);

CREATE TABLE "discovery_explorations" (
  "id" uuid PRIMARY KEY,
  "owner_member_id" uuid,
  "owner_guest_id" uuid,
  "state_version" int NOT NULL,
  "board" jsonb,
  "pinned_refs" jsonb NOT NULL,
  "excluded_refs" jsonb NOT NULL,
  "region_id" uuid,
  "expires_at" timestamptz,
  "deleted_at" timestamptz,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL
);

CREATE TABLE "discovery_runs" (
  "id" uuid PRIMARY KEY,
  "exploration_id" uuid NOT NULL,
  "actor_key" varchar NOT NULL,
  "base_version" int NOT NULL,
  "status" discovery_run_status NOT NULL,
  "stage" varchar,
  "outcome" varchar,
  "clarification" jsonb,
  "created_at" timestamptz NOT NULL,
  "deadline_at" timestamptz NOT NULL,
  "started_at" timestamptz,
  "generation" int NOT NULL,
  "error_code" varchar,
  "engine" varchar NOT NULL
);

CREATE TABLE "discovery_run_commands" (
  "actor_key" varchar NOT NULL,
  "operation" varchar NOT NULL,
  "command_key" uuid NOT NULL,
  "request_hash" varchar NOT NULL,
  "run_id" uuid NOT NULL,
  "result_status" discovery_run_status NOT NULL,
  "result_stage" varchar,
  "result_outcome" varchar,
  "result_generation" int NOT NULL,
  "created_at" timestamptz NOT NULL,
  "expires_at" timestamptz NOT NULL,
  PRIMARY KEY ("actor_key", "operation", "command_key")
);

CREATE TABLE "discovery_proposals" (
  "id" uuid PRIMARY KEY,
  "run_id" uuid UNIQUE NOT NULL,
  "exploration_id" uuid NOT NULL,
  "base_version" int NOT NULL,
  "ordered_refs" jsonb NOT NULL,
  "reasons" jsonb NOT NULL,
  "evidence" jsonb NOT NULL,
  "expires_at" timestamptz NOT NULL,
  "status" discovery_proposal_status NOT NULL
);

CREATE TABLE "discovery_turns" (
  "id" uuid PRIMARY KEY,
  "exploration_id" uuid NOT NULL,
  "client_turn_id" uuid NOT NULL,
  "query" text NOT NULL,
  "created_at" timestamptz NOT NULL
);

CREATE TABLE "journey_saved_journeys" (
  "id" uuid PRIMARY KEY,
  "member_id" uuid NOT NULL,
  "source_exploration_id" uuid,
  "source_version" int NOT NULL,
  "saved_at" timestamptz NOT NULL,
  "title" varchar NOT NULL,
  "snapshot" jsonb NOT NULL,
  "snapshot_hash" varchar NOT NULL
);

CREATE TABLE "journey_saved_resources" (
  "id" uuid PRIMARY KEY,
  "member_id" uuid NOT NULL,
  "resource_type" journey_saved_resource_type NOT NULL,
  "resource_id" uuid NOT NULL,
  "saved_at" timestamptz NOT NULL
);

CREATE TABLE "community_visit_reviews" (
  "id" uuid PRIMARY KEY,
  "member_id" uuid NOT NULL,
  "place_id" uuid NOT NULL,
  "text" text NOT NULL,
  "status" community_review_status NOT NULL,
  "created_at" timestamptz NOT NULL,
  "deleted_at" timestamptz
);

CREATE TABLE "community_review_likes" (
  "review_id" uuid NOT NULL,
  "member_id" uuid NOT NULL,
  "created_at" timestamptz NOT NULL,
  PRIMARY KEY ("review_id", "member_id")
);

CREATE TABLE "community_review_reports" (
  "id" uuid PRIMARY KEY,
  "review_id" uuid NOT NULL,
  "reporter_member_id" uuid NOT NULL,
  "reason" varchar NOT NULL,
  "detail" text,
  "status" community_report_status NOT NULL,
  "created_at" timestamptz NOT NULL,
  "resolved_at" timestamptz
);

CREATE TABLE "community_review_moderation_actions" (
  "id" uuid PRIMARY KEY,
  "review_id" uuid NOT NULL,
  "actor_type" varchar NOT NULL,
  "actor_ref" varchar,
  "previous_status" community_review_status NOT NULL,
  "next_status" community_review_status NOT NULL,
  "reason" varchar NOT NULL,
  "created_at" timestamptz NOT NULL
);

CREATE TABLE "operations_idempotency" (
  "actor_key" varchar NOT NULL,
  "operation" varchar NOT NULL,
  "key" uuid NOT NULL,
  "request_hash" varchar NOT NULL,
  "response_status" int NOT NULL,
  "response_body" jsonb,
  "expires_at" timestamptz NOT NULL,
  PRIMARY KEY ("actor_key", "operation", "key")
);

CREATE TABLE "operations_admission" (
  "scope_key" varchar PRIMARY KEY,
  "window_start" timestamptz NOT NULL,
  "consumed" int NOT NULL,
  "active_count" int NOT NULL
);

CREATE TABLE "operations_admission_audit" (
  "id" uuid PRIMARY KEY,
  "scope_key" varchar NOT NULL,
  "operation" varchar NOT NULL,
  "subject_type" varchar NOT NULL,
  "window_start" timestamptz NOT NULL,
  "decision" varchar NOT NULL,
  "reason" varchar,
  "limit_value" int NOT NULL,
  "consumed_after" int NOT NULL,
  "active_after" int NOT NULL,
  "retry_after_ms" bigint NOT NULL,
  "occurred_at" timestamptz NOT NULL
);

CREATE TABLE "operations_sync_schedules" (
  "dataset" varchar PRIMARY KEY,
  "timezone" varchar NOT NULL,
  "local_time" varchar NOT NULL,
  "enabled" boolean NOT NULL,
  "next_due_at" timestamptz NOT NULL
);

CREATE TABLE "operations_sync_runs" (
  "id" uuid PRIMARY KEY,
  "dataset" varchar NOT NULL,
  "scheduled_for" timestamptz NOT NULL,
  "attempt" int NOT NULL,
  "status" operations_sync_run_status NOT NULL,
  "revision_id" uuid,
  "started_at" timestamptz,
  "finished_at" timestamptz,
  "next_attempt_at" timestamptz,
  "error_code" varchar,
  "counts" jsonb
);

CREATE TABLE "operations_sync_checkpoints" (
  "run_id" uuid NOT NULL,
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
  "lease_until" timestamptz NOT NULL
);

CREATE TABLE "operations_sync_watermarks" (
  "dataset" varchar PRIMARY KEY,
  "source_modified_at" varchar,
  "external_id" varchar,
  "last_full_success_at" timestamptz,
  "last_success_at" timestamptz,
  "revision_id" uuid NOT NULL
);

CREATE TABLE "operations_sync_quarantine" (
  "run_id" uuid NOT NULL,
  "record_key" varchar NOT NULL,
  "error_code" varchar NOT NULL,
  "payload_hash" varchar NOT NULL,
  "redacted_payload" jsonb,
  "expires_at" timestamptz NOT NULL,
  PRIMARY KEY ("run_id", "record_key")
);

CREATE TABLE "ai_documents" (
  "document_id" uuid NOT NULL,
  "revision" varchar NOT NULL,
  "content_hash" varchar NOT NULL,
  "active" boolean NOT NULL,
  "source_type" varchar NOT NULL,
  "source_ref" varchar NOT NULL,
  "metadata" jsonb,
  PRIMARY KEY ("document_id", "revision")
);

CREATE TABLE "ai_chunks" (
  "chunk_id" uuid PRIMARY KEY,
  "document_id" uuid NOT NULL,
  "revision" varchar NOT NULL,
  "position" int NOT NULL,
  "text" text NOT NULL,
  "offsets" jsonb
);

CREATE TABLE "ai_embeddings" (
  "chunk_id" uuid NOT NULL,
  "embedding_profile_id" varchar NOT NULL,
  "vector" vector,
  PRIMARY KEY ("chunk_id", "embedding_profile_id")
);

CREATE TABLE "ai_corpus_sync_runs" (
  "id" uuid PRIMARY KEY,
  "source_revision" varchar NOT NULL,
  "source_manifest_hash" varchar NOT NULL,
  "status" varchar NOT NULL,
  "started_at" timestamptz NOT NULL,
  "finished_at" timestamptz,
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

ALTER TABLE "audio_odii_stories" ADD FOREIGN KEY ("spot_id") REFERENCES "audio_odii_spots" ("id");

ALTER TABLE "audio_spot_versions" ADD FOREIGN KEY ("spot_id") REFERENCES "audio_odii_spots" ("id");

ALTER TABLE "audio_story_versions" ADD FOREIGN KEY ("story_id") REFERENCES "audio_odii_stories" ("id");

ALTER TABLE "audio_story_versions" ADD FOREIGN KEY ("spot_id") REFERENCES "audio_odii_spots" ("id");

ALTER TABLE "audio_subtitle_lines" ADD FOREIGN KEY ("story_id") REFERENCES "audio_odii_stories" ("id");

ALTER TABLE "audio_place_odii_links" ADD FOREIGN KEY ("spot_id") REFERENCES "audio_odii_spots" ("id");

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
