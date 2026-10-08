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

CREATE TYPE "content_tag_source" AS ENUM (
  'GENERATED',
  'PINNED',
  'OPERATOR'
);

CREATE TYPE "content_tag_override_target" AS ENUM (
  'PLACE',
  'ODII_STORY'
);

CREATE TYPE "content_tag_override_action" AS ENUM (
  'PIN',
  'HIDE'
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
  "pkce_verifier_hash" varchar NOT NULL,
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

CREATE TABLE "identity_admin_accounts" (
  "id" uuid PRIMARY KEY,
  "email" varchar NOT NULL,
  "password_hash" varchar NOT NULL,
  "nickname" varchar NOT NULL,
  "role" varchar NOT NULL,
  "status" varchar NOT NULL,
  "last_login_at" timestamptz,
  "tokens_valid_after" timestamptz NOT NULL,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL
);

CREATE TABLE "identity_admin_access_token_revocations" (
  "jti_hash" varchar PRIMARY KEY,
  "admin_id" uuid NOT NULL,
  "expires_at" timestamptz NOT NULL,
  "revoked_at" timestamptz NOT NULL
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

CREATE TABLE "catalog_place_public_ids" (
  "public_id" varchar PRIMARY KEY,
  "place_id" uuid UNIQUE NOT NULL,
  "created_at" timestamptz NOT NULL
);

CREATE TABLE "catalog_external_places" (
  "provider" varchar NOT NULL,
  "external_id" varchar(128) NOT NULL,
  "place_id" uuid UNIQUE NOT NULL,
  "public_place_id" varchar UNIQUE NOT NULL,
  "name" varchar(100) NOT NULL,
  "region_code" varchar NOT NULL,
  "location" geography NOT NULL,
  "provenance" varchar NOT NULL,
  "created_at" timestamptz NOT NULL,
  PRIMARY KEY ("provider", "external_id")
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

CREATE TABLE "map_place_read_projection" (
  "revision_id" uuid NOT NULL,
  "place_id" uuid NOT NULL,
  "public_id" varchar NOT NULL,
  "name" varchar NOT NULL,
  "normalized_name" varchar NOT NULL,
  "status" catalog_place_status NOT NULL,
  "location_geom" geometry NOT NULL,
  "sido_code" varchar,
  "sigungu_code" varchar,
  "eupmyeondong_code" varchar,
  "display_category" varchar NOT NULL,
  "thumbnail_url" text,
  "summary" text,
  "sort_key" varchar NOT NULL,
  PRIMARY KEY ("revision_id", "place_id")
);

CREATE TABLE "map_place_category_projection" (
  "revision_id" uuid NOT NULL,
  "place_id" uuid NOT NULL,
  "canonical_category" varchar NOT NULL,
  PRIMARY KEY ("revision_id", "place_id", "canonical_category")
);

CREATE TABLE "map_scope_count_projection" (
  "revision_id" uuid NOT NULL,
  "scope_type" varchar NOT NULL,
  "region_code" varchar NOT NULL,
  "canonical_category" varchar NOT NULL,
  "place_count" int NOT NULL,
  PRIMARY KEY ("revision_id", "scope_type", "region_code", "canonical_category")
);

CREATE TABLE "map_projection_publications" (
  "revision_id" uuid NOT NULL,
  "projection_name" varchar NOT NULL,
  "mapping_version" varchar NOT NULL,
  "row_count" int NOT NULL,
  "checksum" varchar NOT NULL,
  "published_at" timestamptz NOT NULL,
  "status" catalog_dataset_status NOT NULL,
  PRIMARY KEY ("revision_id", "projection_name")
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

CREATE TABLE "catalog_place_content_tag_versions" (
  "revision_id" uuid NOT NULL,
  "place_id" uuid NOT NULL,
  "position" int NOT NULL,
  "label" varchar NOT NULL,
  "score" numeric NOT NULL,
  "source" content_tag_source NOT NULL,
  "algorithm_version" varchar NOT NULL,
  "source_hash" varchar NOT NULL,
  "generated_at" timestamptz NOT NULL,
  PRIMARY KEY ("revision_id", "place_id", "position")
);

CREATE TABLE "content_tag_overrides" (
  "id" uuid PRIMARY KEY,
  "target_type" content_tag_override_target NOT NULL,
  "target_id" uuid NOT NULL,
  "label" varchar NOT NULL,
  "action" content_tag_override_action NOT NULL,
  "reason" text,
  "created_by" uuid,
  "created_at" timestamptz NOT NULL,
  "expires_at" timestamptz
);

CREATE TABLE "k_contents" (
  "id" uuid PRIMARY KEY,
  "title" varchar NOT NULL,
  "normalized_title" varchar NOT NULL,
  "work_type" varchar NOT NULL,
  "release_year" smallint,
  "season_key" varchar,
  "status" varchar NOT NULL,
  "verified_by" varchar,
  "verified_at" timestamptz,
  "rule_version" varchar,
  "model_version" varchar,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL
);

CREATE TABLE "k_content_aliases" (
  "id" uuid PRIMARY KEY,
  "k_content_id" uuid NOT NULL,
  "alias_text" varchar NOT NULL,
  "normalized_alias" varchar NOT NULL,
  "created_at" timestamptz NOT NULL
);

CREATE TABLE "k_content_metadata_sources" (
  "id" uuid PRIMARY KEY,
  "k_content_id" uuid NOT NULL,
  "canonical_url" text NOT NULL,
  "source_type" varchar NOT NULL,
  "title" text NOT NULL,
  "publisher" varchar,
  "excerpt" text,
  "document_location" text,
  "published_at" timestamptz,
  "observed_at" timestamptz NOT NULL,
  "status" varchar NOT NULL,
  "verified_by" varchar,
  "verified_at" timestamptz,
  "rule_version" varchar,
  "model_version" varchar
);

CREATE TABLE "k_content_units" (
  "id" uuid PRIMARY KEY,
  "k_content_id" uuid NOT NULL,
  "unit_type" varchar NOT NULL,
  "unit_key" varchar NOT NULL,
  "title" varchar
);

CREATE TABLE "k_content_creative_parties" (
  "id" uuid PRIMARY KEY,
  "name" varchar NOT NULL,
  "normalized_name" varchar NOT NULL,
  "party_type" varchar NOT NULL,
  "status" varchar NOT NULL
);

CREATE TABLE "k_content_party_aliases" (
  "id" uuid PRIMARY KEY,
  "party_id" uuid NOT NULL,
  "alias_text" varchar NOT NULL,
  "normalized_alias" varchar NOT NULL
);

CREATE TABLE "k_content_credits" (
  "id" uuid PRIMARY KEY,
  "k_content_id" uuid NOT NULL,
  "party_id" uuid NOT NULL,
  "source_id" uuid NOT NULL,
  "role" varchar NOT NULL
);

CREATE TABLE "k_content_place_relations" (
  "id" uuid PRIMARY KEY,
  "place_id" uuid NOT NULL,
  "k_content_id" uuid NOT NULL,
  "relation_type" varchar NOT NULL,
  "status" varchar NOT NULL,
  "confidence" decimal,
  "verified_by" varchar,
  "verified_at" timestamptz,
  "rule_version" varchar,
  "model_version" varchar,
  "review_reason" varchar
);

CREATE TABLE "k_content_relation_evidence" (
  "id" uuid PRIMARY KEY,
  "relation_id" uuid NOT NULL,
  "canonical_url" text NOT NULL,
  "source_type" varchar NOT NULL,
  "title" text NOT NULL,
  "publisher" varchar,
  "filming_excerpt" text NOT NULL,
  "document_location" text,
  "published_at" timestamptz,
  "observed_at" timestamptz NOT NULL,
  "status" varchar NOT NULL,
  "verified_by" varchar,
  "verified_at" timestamptz,
  "rule_version" varchar,
  "model_version" varchar
);

CREATE TABLE "k_content_appearance_contexts" (
  "id" uuid PRIMARY KEY,
  "relation_id" uuid NOT NULL,
  "k_content_id" uuid NOT NULL,
  "unit_id" uuid,
  "evidence_id" uuid NOT NULL,
  "scene_description" text,
  "document_location" text
);

CREATE TABLE "k_content_tags" (
  "id" uuid PRIMARY KEY,
  "code" varchar UNIQUE NOT NULL,
  "scope" varchar NOT NULL,
  "group_code" varchar NOT NULL,
  "label_ko" varchar NOT NULL,
  "label_en" varchar,
  "active" boolean NOT NULL,
  "policy_version" varchar NOT NULL
);

CREATE TABLE "k_content_tag_aliases" (
  "id" uuid PRIMARY KEY,
  "tag_id" uuid NOT NULL,
  "scope" varchar NOT NULL,
  "group_code" varchar NOT NULL,
  "alias_text" varchar NOT NULL,
  "normalized_alias" varchar NOT NULL
);

CREATE TABLE "k_content_work_tags" (
  "k_content_id" uuid NOT NULL,
  "tag_id" uuid NOT NULL,
  "source_id" uuid NOT NULL,
  "status" varchar NOT NULL,
  PRIMARY KEY ("k_content_id", "tag_id")
);

CREATE TABLE "k_content_relation_tags" (
  "relation_id" uuid NOT NULL,
  "tag_id" uuid NOT NULL,
  "evidence_id" uuid NOT NULL,
  "status" varchar NOT NULL,
  PRIMARY KEY ("relation_id", "tag_id")
);

CREATE TABLE "k_content_tag_candidates" (
  "id" uuid PRIMARY KEY,
  "scope" varchar NOT NULL,
  "k_content_id" uuid,
  "relation_id" uuid,
  "source_id" uuid,
  "evidence_id" uuid,
  "raw_label" varchar NOT NULL,
  "normalized_label" varchar NOT NULL,
  "group_hint" varchar NOT NULL,
  "supporting_quote" text NOT NULL,
  "extraction_run_id" uuid,
  "model_version" varchar,
  "status" varchar NOT NULL,
  "resolved_tag_id" uuid
);

CREATE TABLE "k_content_work_summary_points" (
  "id" uuid PRIMARY KEY,
  "k_content_id" uuid NOT NULL,
  "position" smallint NOT NULL,
  "summary_text" varchar NOT NULL,
  "source_id" uuid NOT NULL,
  "status" varchar NOT NULL,
  "generated_at" timestamptz,
  "prompt_version" varchar,
  "source_fingerprint" varchar,
  "edited_by" varchar
);

CREATE TABLE "k_content_relation_summary_points" (
  "id" uuid PRIMARY KEY,
  "relation_id" uuid NOT NULL,
  "position" smallint NOT NULL,
  "summary_text" varchar NOT NULL,
  "evidence_id" uuid NOT NULL,
  "status" varchar NOT NULL,
  "generated_at" timestamptz,
  "prompt_version" varchar,
  "source_fingerprint" varchar,
  "edited_by" varchar
);

CREATE TABLE "k_content_research_jobs" (
  "id" uuid PRIMARY KEY,
  "place_id" uuid NOT NULL,
  "reason" varchar NOT NULL,
  "source_fingerprint" varchar NOT NULL,
  "input_json" jsonb NOT NULL,
  "status" varchar NOT NULL,
  "attempts" integer NOT NULL,
  "requeue_epoch" integer NOT NULL,
  "max_attempts" integer NOT NULL,
  "available_at" timestamptz NOT NULL,
  "lease_owner" varchar,
  "lease_token_hash" varchar,
  "lease_expires_at" timestamptz,
  "result_status" varchar,
  "result_json" jsonb,
  "completed_at" timestamptz,
  "last_failure_code" varchar,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL
);

CREATE TABLE "k_content_research_runs" (
  "id" uuid PRIMARY KEY,
  "job_id" uuid NOT NULL,
  "requeue_epoch" integer NOT NULL,
  "attempt" integer NOT NULL,
  "worker_id" varchar NOT NULL,
  "started_at" timestamptz NOT NULL,
  "finished_at" timestamptz,
  "outcome" varchar,
  "failure_code" varchar
);

CREATE TABLE "k_content_research_evidence" (
  "id" uuid PRIMARY KEY,
  "job_id" uuid NOT NULL,
  "canonical_url" text NOT NULL,
  "title" text NOT NULL,
  "publisher" varchar,
  "excerpt" varchar NOT NULL,
  "observed_at" timestamptz NOT NULL
);

CREATE TABLE "k_content_research_receipts" (
  "id" uuid PRIMARY KEY,
  "job_id" uuid NOT NULL,
  "idempotency_key" varchar NOT NULL,
  "request_hash" varchar NOT NULL,
  "outcome" varchar NOT NULL,
  "worker_id" varchar NOT NULL,
  "lease_token_hash" varchar NOT NULL,
  "created_at" timestamptz NOT NULL
);

CREATE TABLE "k_content_research_events" (
  "id" uuid PRIMARY KEY,
  "job_id" uuid NOT NULL,
  "event_type" varchar NOT NULL,
  "actor" varchar NOT NULL,
  "detail_code" varchar,
  "created_at" timestamptz NOT NULL
);

CREATE TABLE "audio_odii_spots" (
  "id" uuid PRIMARY KEY,
  "provider" varchar NOT NULL,
  "tid" varchar NOT NULL,
  "tlid" varchar NOT NULL,
  "lang_code" varchar NOT NULL,
  "created_at" timestamptz NOT NULL,
  "public_id" uuid NOT NULL
);

CREATE TABLE "audio_odii_stories" (
  "id" uuid PRIMARY KEY,
  "spot_id" uuid NOT NULL,
  "provider" varchar NOT NULL,
  "stid" varchar NOT NULL,
  "stlid" varchar NOT NULL,
  "lang_code" varchar NOT NULL,
  "created_at" timestamptz NOT NULL,
  "public_id" uuid NOT NULL
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

CREATE TABLE "audio_story_content_tag_versions" (
  "revision_id" uuid NOT NULL,
  "story_id" uuid NOT NULL,
  "position" int NOT NULL,
  "label" varchar NOT NULL,
  "score" numeric NOT NULL,
  "source" content_tag_source NOT NULL,
  "algorithm_version" varchar NOT NULL,
  "source_hash" varchar NOT NULL,
  "generated_at" timestamptz NOT NULL,
  PRIMARY KEY ("revision_id", "story_id", "position")
);

CREATE TABLE "audio_story_play_events" (
  "id" uuid PRIMARY KEY,
  "story_id" varchar NOT NULL,
  "occurred_at" timestamptz NOT NULL
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
  "lease_expires_at" timestamptz,
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

CREATE TABLE "journey_saved_places" (
  "id" uuid PRIMARY KEY,
  "member_id" uuid NOT NULL,
  "place_id" varchar NOT NULL,
  "saved_at" timestamptz NOT NULL
);

CREATE TABLE "journey_saved_odii_stories" (
  "id" uuid PRIMARY KEY,
  "member_id" uuid NOT NULL,
  "story_id" varchar NOT NULL,
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
  "scope" varchar NOT NULL DEFAULT 'ALL',
  "requested_at" timestamptz,
  "scheduled_for" timestamptz NOT NULL,
  "attempt" int NOT NULL,
  "status" operations_sync_run_status NOT NULL,
  "revision_id" uuid,
  "started_at" timestamptz,
  "finished_at" timestamptz,
  "next_attempt_at" timestamptz,
  "error_code" varchar,
  "counts" jsonb,
  "current_stage" varchar,
  "progress_completed" bigint,
  "progress_total" bigint
);

CREATE TABLE "operations_sync_failures" (
  "id" uuid PRIMARY KEY,
  "run_id" uuid NOT NULL,
  "occurred_at" timestamptz NOT NULL,
  "endpoint" varchar,
  "content_id" varchar,
  "error_code" varchar NOT NULL,
  "message" varchar NOT NULL,
  "retryable" boolean NOT NULL
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

CREATE UNIQUE INDEX ON "identity_external_accounts" ("provider", "issuer", "subject");

CREATE INDEX ON "identity_external_accounts" ("member_id");

CREATE INDEX ON "identity_sessions" ("member_id");

CREATE INDEX ON "identity_sessions" ("absolute_expires_at");

CREATE INDEX ON "identity_guests" ("expires_at");

CREATE INDEX ON "identity_oauth_states" ("guest_id");

CREATE INDEX ON "identity_oauth_states" ("exploration_id");

CREATE INDEX ON "identity_oauth_states" ("expires_at");

CREATE INDEX ON "identity_exploration_grants" ("expires_at");

CREATE INDEX ON "identity_admin_access_token_revocations" ("expires_at");

CREATE INDEX ON "catalog_region_source_codes" ("region_id");

CREATE INDEX ON "catalog_region_boundaries" USING GIST ("geometry");

CREATE UNIQUE INDEX ON "catalog_dataset_revisions" ("dataset", "id");

CREATE INDEX ON "catalog_dataset_revisions" ("dataset", "status");

CREATE INDEX ON "catalog_external_places" USING GIST ("location");

CREATE UNIQUE INDEX ON "catalog_place_sources" ("provider", "dataset", "external_id", "language");

CREATE INDEX ON "catalog_place_sources" ("place_id");

CREATE INDEX ON "catalog_place_versions" ("region_id");

CREATE INDEX ON "catalog_place_versions" USING GIST ("location");

CREATE INDEX ON "catalog_place_versions" ("status", "category");

CREATE UNIQUE INDEX ON "map_place_read_projection" ("revision_id", "public_id");

CREATE INDEX ON "map_place_read_projection" ("revision_id", "sigungu_code", "sort_key", "place_id");

CREATE INDEX ON "map_place_read_projection" ("revision_id", "sort_key", "place_id");

CREATE INDEX ON "map_place_read_projection" ("revision_id", "status", "display_category", "sort_key", "place_id");

CREATE INDEX ON "map_place_read_projection" USING GIST ("location_geom");

CREATE INDEX ON "map_place_category_projection" ("revision_id", "canonical_category", "place_id");

CREATE INDEX ON "catalog_kto_korean_content_versions" ("contentid");

CREATE INDEX ON "catalog_kto_korean_content_versions" ("areacode", "sigungucode");

CREATE INDEX ON "catalog_kto_korean_content_versions" ("cat1", "cat2", "cat3");

CREATE INDEX ON "catalog_place_image_versions" ("source_ref_id");

CREATE UNIQUE INDEX ON "catalog_place_content_tag_versions" ("revision_id", "place_id", "label");

CREATE INDEX ON "catalog_place_content_tag_versions" ("label", "revision_id");

CREATE UNIQUE INDEX ON "content_tag_overrides" ("target_type", "target_id", "label", "action");

CREATE INDEX ON "content_tag_overrides" ("target_type", "target_id");

CREATE INDEX ON "k_contents" ("work_type", "normalized_title", "release_year", "season_key");

CREATE UNIQUE INDEX ON "k_content_aliases" ("k_content_id", "normalized_alias");

CREATE INDEX ON "k_content_aliases" ("normalized_alias");

CREATE UNIQUE INDEX ON "k_content_metadata_sources" ("k_content_id", "canonical_url");

CREATE UNIQUE INDEX ON "k_content_units" ("k_content_id", "unit_type", "unit_key");

CREATE UNIQUE INDEX ON "k_content_party_aliases" ("party_id", "normalized_alias");

CREATE UNIQUE INDEX ON "k_content_credits" ("k_content_id", "party_id", "role", "source_id");

CREATE UNIQUE INDEX ON "k_content_place_relations" ("place_id", "k_content_id", "relation_type");

CREATE INDEX ON "k_content_place_relations" ("place_id", "status", "k_content_id", "id");

CREATE INDEX ON "k_content_place_relations" ("k_content_id", "status", "place_id", "id");

CREATE UNIQUE INDEX ON "k_content_relation_evidence" ("relation_id", "canonical_url");

CREATE INDEX ON "k_content_relation_evidence" ("relation_id", "status");

CREATE UNIQUE INDEX ON "k_content_tag_aliases" ("scope", "group_code", "normalized_alias");

CREATE UNIQUE INDEX ON "k_content_work_summary_points" ("k_content_id", "position");

CREATE UNIQUE INDEX ON "k_content_relation_summary_points" ("relation_id", "position");

CREATE UNIQUE INDEX ON "k_content_research_jobs" ("place_id", "reason", "source_fingerprint");

CREATE UNIQUE INDEX ON "k_content_research_runs" ("job_id", "requeue_epoch", "attempt");

CREATE UNIQUE INDEX ON "k_content_research_evidence" ("job_id", "canonical_url");

CREATE UNIQUE INDEX ON "k_content_research_receipts" ("job_id", "idempotency_key");

CREATE UNIQUE INDEX ON "audio_odii_spots" ("provider", "tid", "tlid");

CREATE UNIQUE INDEX ON "audio_odii_spots" ("public_id", "lang_code");

CREATE UNIQUE INDEX ON "audio_odii_stories" ("provider", "stid", "stlid");

CREATE INDEX ON "audio_odii_stories" ("spot_id");

CREATE UNIQUE INDEX ON "audio_odii_stories" ("public_id", "lang_code");

CREATE INDEX ON "audio_spot_versions" USING GIST ("location");

CREATE INDEX ON "audio_story_versions" ("revision_id", "spot_id");

CREATE INDEX ON "audio_story_versions" ("revision_id", "source_modified_at", "story_id");

CREATE INDEX ON "audio_place_odii_links" ("spot_id");

CREATE UNIQUE INDEX "audio_place_odii_links_one_approved_per_spot_uq" ON "audio_place_odii_links" ("spot_id");

CREATE UNIQUE INDEX ON "audio_story_content_tag_versions" ("revision_id", "story_id", "label");

CREATE INDEX ON "audio_story_content_tag_versions" ("label", "revision_id");

CREATE INDEX ON "audio_story_play_events" ("story_id", "occurred_at");

CREATE INDEX ON "audio_story_play_events" ("occurred_at", "story_id");

CREATE INDEX ON "insights_visitor_observations" ("region_id", "basis_date");

CREATE UNIQUE INDEX ON "insights_tourism_targets" ("provider", "source_target_key");

CREATE INDEX ON "insights_tourism_targets" ("region_id");

CREATE INDEX ON "insights_concentration_observations" ("target_id", "basis_date");

CREATE INDEX ON "discovery_explorations" ("owner_member_id", "updated_at");

CREATE INDEX ON "discovery_explorations" ("owner_guest_id", "updated_at");

CREATE INDEX ON "discovery_runs" ("exploration_id");

CREATE INDEX ON "discovery_runs" ("actor_key");

CREATE INDEX ON "discovery_runs" ("deadline_at");

CREATE INDEX ON "discovery_run_commands" ("expires_at");

CREATE INDEX ON "discovery_proposals" ("exploration_id", "status");

CREATE UNIQUE INDEX ON "discovery_turns" ("exploration_id", "client_turn_id");

CREATE INDEX ON "discovery_turns" ("exploration_id", "created_at");

CREATE UNIQUE INDEX ON "journey_saved_journeys" ("member_id", "source_exploration_id", "source_version");

CREATE INDEX ON "journey_saved_journeys" ("member_id", "saved_at", "id");

CREATE UNIQUE INDEX ON "journey_saved_resources" ("member_id", "resource_type", "resource_id");

CREATE INDEX ON "journey_saved_resources" ("member_id", "resource_type", "saved_at", "id");

CREATE UNIQUE INDEX ON "journey_saved_places" ("member_id", "place_id");

CREATE INDEX ON "journey_saved_places" ("saved_at", "id");

CREATE INDEX ON "journey_saved_places" ("place_id", "saved_at");

CREATE UNIQUE INDEX ON "journey_saved_odii_stories" ("member_id", "story_id");

CREATE INDEX ON "journey_saved_odii_stories" ("saved_at", "id");

CREATE INDEX ON "journey_saved_odii_stories" ("story_id", "saved_at");

CREATE INDEX ON "community_visit_reviews" ("created_at", "id");

CREATE INDEX ON "community_visit_reviews" ("place_id", "created_at", "id");

CREATE INDEX ON "community_visit_reviews" ("member_id");

CREATE INDEX ON "community_review_likes" ("member_id");

CREATE UNIQUE INDEX ON "community_review_reports" ("review_id", "reporter_member_id");

CREATE INDEX ON "community_review_reports" ("status", "created_at");

CREATE INDEX ON "community_review_moderation_actions" ("review_id", "created_at");

CREATE INDEX ON "operations_idempotency" ("expires_at");

CREATE INDEX ON "operations_admission_audit" ("scope_key", "occurred_at");

CREATE INDEX ON "operations_admission_audit" ("operation", "decision", "occurred_at");

CREATE UNIQUE INDEX ON "operations_sync_runs" ("dataset", "scheduled_for", "attempt");

CREATE INDEX ON "operations_sync_runs" ("dataset", "status");

CREATE INDEX ON "operations_sync_failures" ("run_id", "occurred_at", "id");

CREATE INDEX ON "operations_sync_quarantine" ("expires_at");

CREATE INDEX ON "ai_documents" ("source_type", "source_ref");

CREATE UNIQUE INDEX ON "ai_chunks" ("document_id", "revision", "position");

CREATE UNIQUE INDEX ON "ai_corpus_sync_runs" ("source_revision", "source_manifest_hash");

COMMENT ON COLUMN "identity_external_accounts"."provider" IS 'KAKAO active for MVP; GOOGLE/NAVER can be added through allowlist';

COMMENT ON COLUMN "identity_oauth_states"."pkce_verifier_hash" IS 'SHA-256 hash used for one-time OAuth PKCE verification';

COMMENT ON COLUMN "identity_admin_access_token_revocations"."jti_hash" IS 'SHA-256 hash of the JWT ID; raw JTI values are never persisted';

COMMENT ON COLUMN "catalog_region_boundaries"."geometry" IS 'PostGIS MultiPolygon(4326)';

COMMENT ON COLUMN "catalog_place_public_ids"."public_id" IS 'Stable FE-visible p-lowercase-kebab-case ID';

COMMENT ON COLUMN "catalog_external_places"."provider" IS 'MVP: KAKAO';

COMMENT ON COLUMN "catalog_external_places"."public_place_id" IS 'Opaque p-ext-{32 lowercase hex}';

COMMENT ON COLUMN "catalog_external_places"."region_code" IS 'Canonical kr-* or kr-unassigned';

COMMENT ON COLUMN "catalog_external_places"."location" IS 'PostGIS Point(4326)';

COMMENT ON COLUMN "catalog_external_places"."provenance" IS 'CLIENT_ASSERTED';

COMMENT ON COLUMN "catalog_place_sources"."external_id" IS 'KTO Korean contentid, future source id, or normalized source key';

COMMENT ON COLUMN "catalog_place_versions"."location" IS 'PostGIS Point(4326)';

COMMENT ON COLUMN "map_place_read_projection"."location_geom" IS 'PostGIS Point(4326), WGS84 geometry for bbox queries';

COMMENT ON COLUMN "map_scope_count_projection"."scope_type" IS 'DISTRICT or REGION';

COMMENT ON COLUMN "k_contents"."work_type" IS 'DRAMA/MOVIE/VARIETY/MUSIC_VIDEO';

COMMENT ON COLUMN "k_content_place_relations"."relation_type" IS 'FILMING_LOCATION';

COMMENT ON COLUMN "audio_odii_spots"."public_id" IS 'V033 generated stored: Java UUID.nameUUIDFromBytes(provider:odii-spot-:tid) compatible';

COMMENT ON COLUMN "audio_odii_stories"."public_id" IS 'V033 generated stored: Java UUID.nameUUIDFromBytes(provider:odii-story-:stid) compatible';

COMMENT ON COLUMN "audio_spot_versions"."location" IS 'PostGIS Point(4326)';

COMMENT ON COLUMN "audio_story_play_events"."story_id" IS '공개 odii story ID';

COMMENT ON COLUMN "insights_visitor_observations"."visitor_type" IS 'local, domestic visitor, foreign visitor, or provider code';

COMMENT ON TABLE "discovery_explorations" IS 'Executable DDL must enforce exactly one owner: (owner_member_id IS NULL) <> (owner_guest_id IS NULL).';

COMMENT ON TABLE "discovery_runs" IS 'Executable DDL must enforce status/stage/outcome compatibility and partial unique indexes: one QUEUED or RUNNING run per exploration and per actor_key.';

COMMENT ON COLUMN "discovery_runs"."lease_expires_at" IS 'RUNNING worker lease; sweeper terminalizes expired leases';

COMMENT ON TABLE "discovery_run_commands" IS 'Durable command receipt. The executable migration enforces the operation/stage allowlists, positive generation, expiry, and composite primary key.';

COMMENT ON COLUMN "journey_saved_resources"."resource_id" IS 'PLACE -> catalog_place_identity.id for every public canonical tourism place (hanok, stay, cafe, experience, market, attraction); ODII_STORY -> audio_odii_stories.id only for standalone replay. Enforced by application eligibility port.';

COMMENT ON COLUMN "journey_saved_places"."place_id" IS '공개 place ID (예: p-jeonju-hanok-village)';

COMMENT ON COLUMN "journey_saved_odii_stories"."story_id" IS '공개 odii story ID (예: odii-story-jeonju-hanok-01)';

COMMENT ON COLUMN "community_review_reports"."reason" IS 'SPAM, ABUSE, PERSONAL_DATA, COPYRIGHT, OTHER';

COMMENT ON COLUMN "community_review_moderation_actions"."actor_type" IS 'SYSTEM or OPERATOR; never a public member action';

COMMENT ON TABLE "operations_sync_runs" IS 'Latest-run migration index uses (dataset, COALESCE(started_at, scheduled_for) DESC, id DESC).';

COMMENT ON COLUMN "operations_sync_failures"."message" IS 'Sanitized; no credentials, personal data, or upstream payload';

COMMENT ON COLUMN "ai_documents"."source_ref" IS 'Stable source identifier from Spring corpus export; not a DB FK';

COMMENT ON COLUMN "ai_embeddings"."vector" IS 'FastAPI-managed pgvector index; enabled only after RAG evaluation gate';

COMMENT ON COLUMN "ai_corpus_sync_runs"."status" IS 'RUNNING, SUCCEEDED, FAILED';

ALTER TABLE "identity_oauth_states" ADD FOREIGN KEY ("exploration_id") REFERENCES "discovery_explorations" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "identity_exploration_grants" ADD FOREIGN KEY ("exploration_id") REFERENCES "discovery_explorations" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "discovery_explorations" ADD FOREIGN KEY ("owner_member_id") REFERENCES "identity_members" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "discovery_explorations" ADD FOREIGN KEY ("owner_guest_id") REFERENCES "identity_guests" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "discovery_explorations" ADD FOREIGN KEY ("region_id") REFERENCES "catalog_regions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "journey_saved_journeys" ADD FOREIGN KEY ("member_id") REFERENCES "identity_members" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "journey_saved_journeys" ADD FOREIGN KEY ("source_exploration_id") REFERENCES "discovery_explorations" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "community_visit_reviews" ADD FOREIGN KEY ("member_id") REFERENCES "identity_members" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "community_visit_reviews" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "community_review_likes" ADD FOREIGN KEY ("member_id") REFERENCES "identity_members" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "community_review_reports" ADD FOREIGN KEY ("reporter_member_id") REFERENCES "identity_members" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "audio_place_odii_links" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "audio_revision_stages" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "insights_visitor_observations" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "insights_visitor_observations" ADD FOREIGN KEY ("region_id") REFERENCES "catalog_regions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "insights_tourism_targets" ADD FOREIGN KEY ("region_id") REFERENCES "catalog_regions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "insights_target_place_links" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "insights_concentration_observations" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "insights_concentration_observations" ADD FOREIGN KEY ("target_id") REFERENCES "insights_tourism_targets" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "operations_sync_runs" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "operations_sync_watermarks" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_place_public_ids" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_external_places" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_external_places" ADD FOREIGN KEY ("public_place_id") REFERENCES "catalog_place_public_ids" ("public_id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_place_relations" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_research_jobs" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "identity_external_accounts" ADD FOREIGN KEY ("member_id") REFERENCES "identity_members" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "identity_sessions" ADD FOREIGN KEY ("member_id") REFERENCES "identity_members" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "identity_oauth_states" ADD FOREIGN KEY ("guest_id") REFERENCES "identity_guests" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "identity_exploration_grants" ADD FOREIGN KEY ("member_id") REFERENCES "identity_members" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "identity_admin_access_token_revocations" ADD FOREIGN KEY ("admin_id") REFERENCES "identity_admin_accounts" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_regions" ADD FOREIGN KEY ("parent_id") REFERENCES "catalog_regions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_region_source_codes" ADD FOREIGN KEY ("region_id") REFERENCES "catalog_regions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_region_boundaries" ADD FOREIGN KEY ("region_id") REFERENCES "catalog_regions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_dataset_revisions" ADD FOREIGN KEY ("base_revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_active_datasets" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_place_sources" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_place_versions" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_place_versions" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_place_versions" ADD FOREIGN KEY ("source_ref_id") REFERENCES "catalog_place_sources" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_place_versions" ADD FOREIGN KEY ("region_id") REFERENCES "catalog_regions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_kto_korean_content_versions" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_kto_korean_content_versions" ADD FOREIGN KEY ("source_ref_id") REFERENCES "catalog_place_sources" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_kto_korean_intro_versions" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_kto_korean_intro_versions" ADD FOREIGN KEY ("source_ref_id") REFERENCES "catalog_place_sources" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_kto_korean_info_versions" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_kto_korean_info_versions" ADD FOREIGN KEY ("source_ref_id") REFERENCES "catalog_place_sources" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_place_image_versions" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_place_image_versions" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_place_image_versions" ADD FOREIGN KEY ("source_ref_id") REFERENCES "catalog_place_sources" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_hanok_detail_versions" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_hanok_detail_versions" ADD FOREIGN KEY ("place_id") REFERENCES "catalog_place_identity" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_hanok_detail_versions" ADD FOREIGN KEY ("source_ref_id") REFERENCES "catalog_place_sources" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "catalog_place_content_tag_versions" ADD FOREIGN KEY ("revision_id", "place_id") REFERENCES "catalog_place_versions" ("revision_id", "place_id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "map_place_read_projection" ADD FOREIGN KEY ("revision_id", "place_id") REFERENCES "catalog_place_versions" ("revision_id", "place_id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "map_place_category_projection" ADD FOREIGN KEY ("revision_id", "place_id") REFERENCES "catalog_place_versions" ("revision_id", "place_id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "map_scope_count_projection" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "map_projection_publications" ADD FOREIGN KEY ("revision_id") REFERENCES "catalog_dataset_revisions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_research_runs" ADD FOREIGN KEY ("job_id") REFERENCES "k_content_research_jobs" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_research_evidence" ADD FOREIGN KEY ("job_id") REFERENCES "k_content_research_jobs" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_research_receipts" ADD FOREIGN KEY ("job_id") REFERENCES "k_content_research_jobs" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_research_events" ADD FOREIGN KEY ("job_id") REFERENCES "k_content_research_jobs" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_aliases" ADD FOREIGN KEY ("k_content_id") REFERENCES "k_contents" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_metadata_sources" ADD FOREIGN KEY ("k_content_id") REFERENCES "k_contents" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_units" ADD FOREIGN KEY ("k_content_id") REFERENCES "k_contents" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_party_aliases" ADD FOREIGN KEY ("party_id") REFERENCES "k_content_creative_parties" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_credits" ADD FOREIGN KEY ("k_content_id") REFERENCES "k_contents" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_credits" ADD FOREIGN KEY ("party_id") REFERENCES "k_content_creative_parties" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_credits" ADD FOREIGN KEY ("source_id") REFERENCES "k_content_metadata_sources" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_place_relations" ADD FOREIGN KEY ("k_content_id") REFERENCES "k_contents" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_relation_evidence" ADD FOREIGN KEY ("relation_id") REFERENCES "k_content_place_relations" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_appearance_contexts" ADD FOREIGN KEY ("relation_id") REFERENCES "k_content_place_relations" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_appearance_contexts" ADD FOREIGN KEY ("unit_id") REFERENCES "k_content_units" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_appearance_contexts" ADD FOREIGN KEY ("evidence_id") REFERENCES "k_content_relation_evidence" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_tag_aliases" ADD FOREIGN KEY ("tag_id") REFERENCES "k_content_tags" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_work_tags" ADD FOREIGN KEY ("k_content_id") REFERENCES "k_contents" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_work_tags" ADD FOREIGN KEY ("tag_id") REFERENCES "k_content_tags" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_work_tags" ADD FOREIGN KEY ("source_id") REFERENCES "k_content_metadata_sources" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_relation_tags" ADD FOREIGN KEY ("relation_id") REFERENCES "k_content_place_relations" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_relation_tags" ADD FOREIGN KEY ("tag_id") REFERENCES "k_content_tags" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_relation_tags" ADD FOREIGN KEY ("evidence_id") REFERENCES "k_content_relation_evidence" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_tag_candidates" ADD FOREIGN KEY ("k_content_id") REFERENCES "k_contents" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_tag_candidates" ADD FOREIGN KEY ("relation_id") REFERENCES "k_content_place_relations" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_tag_candidates" ADD FOREIGN KEY ("source_id") REFERENCES "k_content_metadata_sources" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_tag_candidates" ADD FOREIGN KEY ("evidence_id") REFERENCES "k_content_relation_evidence" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_tag_candidates" ADD FOREIGN KEY ("resolved_tag_id") REFERENCES "k_content_tags" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_work_summary_points" ADD FOREIGN KEY ("k_content_id") REFERENCES "k_contents" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_work_summary_points" ADD FOREIGN KEY ("source_id") REFERENCES "k_content_metadata_sources" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_relation_summary_points" ADD FOREIGN KEY ("relation_id") REFERENCES "k_content_place_relations" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "k_content_relation_summary_points" ADD FOREIGN KEY ("evidence_id") REFERENCES "k_content_relation_evidence" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "audio_odii_stories" ADD FOREIGN KEY ("spot_id") REFERENCES "audio_odii_spots" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "audio_spot_versions" ADD FOREIGN KEY ("spot_id") REFERENCES "audio_odii_spots" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "audio_story_versions" ADD FOREIGN KEY ("story_id") REFERENCES "audio_odii_stories" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "audio_story_versions" ADD FOREIGN KEY ("spot_id") REFERENCES "audio_odii_spots" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "audio_subtitle_lines" ADD FOREIGN KEY ("story_id") REFERENCES "audio_odii_stories" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "audio_place_odii_links" ADD FOREIGN KEY ("spot_id") REFERENCES "audio_odii_spots" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "audio_story_content_tag_versions" ADD FOREIGN KEY ("revision_id", "story_id") REFERENCES "audio_story_versions" ("revision_id", "story_id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "insights_target_place_links" ADD FOREIGN KEY ("target_id") REFERENCES "insights_tourism_targets" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "discovery_runs" ADD FOREIGN KEY ("exploration_id") REFERENCES "discovery_explorations" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "discovery_run_commands" ADD FOREIGN KEY ("run_id") REFERENCES "discovery_runs" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "discovery_proposals" ADD FOREIGN KEY ("run_id") REFERENCES "discovery_runs" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "discovery_proposals" ADD FOREIGN KEY ("exploration_id") REFERENCES "discovery_explorations" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "discovery_turns" ADD FOREIGN KEY ("exploration_id") REFERENCES "discovery_explorations" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "community_review_likes" ADD FOREIGN KEY ("review_id") REFERENCES "community_visit_reviews" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "community_review_reports" ADD FOREIGN KEY ("review_id") REFERENCES "community_visit_reviews" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "community_review_moderation_actions" ADD FOREIGN KEY ("review_id") REFERENCES "community_visit_reviews" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "operations_sync_checkpoints" ADD FOREIGN KEY ("run_id") REFERENCES "operations_sync_runs" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "operations_sync_quarantine" ADD FOREIGN KEY ("run_id") REFERENCES "operations_sync_runs" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "operations_sync_failures" ADD FOREIGN KEY ("run_id") REFERENCES "operations_sync_runs" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "ai_chunks" ADD FOREIGN KEY ("document_id", "revision") REFERENCES "ai_documents" ("document_id", "revision") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "ai_embeddings" ADD FOREIGN KEY ("chunk_id") REFERENCES "ai_chunks" ("chunk_id") DEFERRABLE INITIALLY IMMEDIATE;
