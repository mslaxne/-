-- Contract 2.0.0. UTC times are integer epoch seconds; IDs are opaque UTF-8 text.
-- Set these on EVERY connection before any transaction.
PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = FULL;
PRAGMA busy_timeout = 250;

CREATE TABLE IF NOT EXISTS scopes (
 scope_id TEXT PRIMARY KEY NOT NULL, owner_id TEXT NOT NULL, contact_id TEXT NOT NULL,
 agent_id TEXT NOT NULL, persona_id TEXT NOT NULL, realm_id TEXT NOT NULL,
 persona_revision INTEGER NOT NULL DEFAULT 1 CHECK(persona_revision>0),
 revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0),
 deletion_epoch INTEGER NOT NULL DEFAULT 0 CHECK(deletion_epoch>=0),
 import_generation INTEGER NOT NULL DEFAULT 1 CHECK(import_generation>0),
 policy_version TEXT NOT NULL DEFAULT '2.0.0',
 config_version TEXT NOT NULL DEFAULT '2.0.0',
 retention_policy TEXT NOT NULL DEFAULT 'independent_evidence' CHECK(retention_policy IN ('independent_evidence','cascade')),
 state TEXT NOT NULL DEFAULT 'active' CHECK(state IN ('active','paused','deleting')),
 auto_learn_blocked INTEGER NOT NULL DEFAULT 0 CHECK(auto_learn_blocked IN (0,1)),
 op_watermark INTEGER NOT NULL DEFAULT 0,
 restore_guard_id TEXT NOT NULL, created_at INTEGER NOT NULL,
 UNIQUE(owner_id,contact_id,agent_id,persona_id,realm_id)
);
CREATE TABLE IF NOT EXISTS entities (
 scope_id TEXT NOT NULL, entity_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('user','agent','person','organization','temporary')),
 display_name TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 1,
 resolution TEXT NOT NULL CHECK(resolution IN ('resolved','ambiguous')),
 created_at INTEGER NOT NULL, PRIMARY KEY(scope_id,entity_id),
 FOREIGN KEY(scope_id) REFERENCES scopes(scope_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS aliases (
 scope_id TEXT NOT NULL, alias_id TEXT NOT NULL, entity_id TEXT NOT NULL,
 normalized TEXT NOT NULL, original TEXT NOT NULL,
 source_message_id TEXT, source_revision INTEGER,
 created_at INTEGER NOT NULL, PRIMARY KEY(scope_id,alias_id),
 UNIQUE(scope_id,normalized,entity_id),
 FOREIGN KEY(scope_id,entity_id) REFERENCES entities(scope_id,entity_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS aliases_lookup ON aliases(scope_id,normalized);
CREATE TABLE IF NOT EXISTS sessions (
 scope_id TEXT NOT NULL, session_id TEXT NOT NULL, next_seq INTEGER NOT NULL DEFAULT 1,
 working_summary TEXT NOT NULL DEFAULT '{}', working_version INTEGER NOT NULL DEFAULT 0,
 working_sources TEXT NOT NULL DEFAULT '[]', deny_before_seq INTEGER NOT NULL DEFAULT 0,
 state TEXT NOT NULL DEFAULT 'open' CHECK(state IN ('open','closed')),
 created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,session_id),
 FOREIGN KEY(scope_id) REFERENCES scopes(scope_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS messages (
 scope_id TEXT NOT NULL, message_id TEXT NOT NULL, session_id TEXT NOT NULL,
 client_message_id TEXT NOT NULL, seq INTEGER NOT NULL CHECK(seq>0),
 revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0),
 role TEXT NOT NULL CHECK(role IN ('user','assistant','tool','config')),
 origin_json TEXT NOT NULL DEFAULT '{}',
 body TEXT, content_sha256 TEXT NOT NULL,
 occurred_at INTEGER NOT NULL, received_at INTEGER NOT NULL, timezone TEXT NOT NULL,
 cache_expires_at INTEGER NOT NULL, host_expires_at INTEGER,
 state TEXT NOT NULL DEFAULT 'live' CHECK(state IN ('live','expired','forgotten')),
 extraction_state TEXT NOT NULL DEFAULT 'pending' CHECK(extraction_state IN ('pending','done','skipped','gap')),
 summary_state TEXT NOT NULL DEFAULT 'pending' CHECK(summary_state IN ('pending','done','gap')),
 PRIMARY KEY(scope_id,message_id), UNIQUE(scope_id,session_id,seq),
 UNIQUE(scope_id,session_id,client_message_id),
 FOREIGN KEY(scope_id,session_id) REFERENCES sessions(scope_id,session_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS messages_expiry ON messages(scope_id,cache_expires_at,state);
CREATE TABLE IF NOT EXISTS message_cleanup (
 scope_id TEXT NOT NULL, message_id TEXT NOT NULL, session_id TEXT NOT NULL,
 client_message_id TEXT NOT NULL, seq INTEGER NOT NULL,
 completed_at INTEGER NOT NULL, body_due_at INTEGER NOT NULL,
 metadata_due_at INTEGER NOT NULL, body_redacted_at INTEGER,
 state TEXT NOT NULL DEFAULT 'pending'
   CHECK(state IN ('pending','redacted','purged','blocked')),
 last_error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,message_id),
 UNIQUE(scope_id,session_id,client_message_id),
 FOREIGN KEY(scope_id) REFERENCES scopes(scope_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS message_cleanup_due
 ON message_cleanup(state,body_due_at,metadata_due_at);
CREATE TABLE IF NOT EXISTS memories (
 scope_id TEXT NOT NULL, memory_id TEXT NOT NULL, version INTEGER NOT NULL CHECK(version>0),
 tier TEXT NOT NULL CHECK(tier IN ('A0','A1','A2')),
 fact_key TEXT NOT NULL, subject_id TEXT NOT NULL, viewpoint_id TEXT,
 predicate TEXT NOT NULL, text TEXT NOT NULL, value_json TEXT NOT NULL,
 epistemic TEXT NOT NULL CHECK(epistemic IN ('configured','declared','observed','inferred')),
 lifecycle TEXT NOT NULL DEFAULT 'active' CHECK(lifecycle IN ('active','needs_review')),
 retracted_at INTEGER,
 pinned INTEGER NOT NULL DEFAULT 0 CHECK(pinned IN (0,1)),
 commitment_state TEXT CHECK(commitment_state IN ('proposed','active','overdue_unknown','completed','cancelled')),
 temporal_json TEXT NOT NULL DEFAULT '{}', retention_score REAL NOT NULL DEFAULT 0.5 CHECK(retention_score>=0 AND retention_score<=1),
 source_anchor INTEGER NOT NULL, expires_at INTEGER,
 created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
 deletion_epoch INTEGER NOT NULL, PRIMARY KEY(scope_id,memory_id),
 UNIQUE(scope_id,fact_key),
 CHECK((tier='A2' AND expires_at IS NOT NULL) OR (tier IN ('A0','A1') AND expires_at IS NULL)),
 CHECK(tier!='A0' OR epistemic IN ('configured','declared')),
 FOREIGN KEY(scope_id,subject_id) REFERENCES entities(scope_id,entity_id),
 FOREIGN KEY(scope_id,viewpoint_id) REFERENCES entities(scope_id,entity_id),
 FOREIGN KEY(scope_id) REFERENCES scopes(scope_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS memories_retrieve ON memories(scope_id,lifecycle,tier);
CREATE INDEX IF NOT EXISTS memories_subject ON memories(scope_id,subject_id,predicate,viewpoint_id);
CREATE INDEX IF NOT EXISTS memories_expiry ON memories(scope_id,expires_at);
CREATE TABLE IF NOT EXISTS evidence (
 scope_id TEXT NOT NULL, evidence_id TEXT NOT NULL, memory_id TEXT NOT NULL,
 source_message_id TEXT NOT NULL, source_revision INTEGER NOT NULL,
 source_kind TEXT NOT NULL CHECK(source_kind IN ('user','assistant','tool','config')),
 quote TEXT NOT NULL, start_codepoint INTEGER NOT NULL CHECK(start_codepoint>=0),
 end_codepoint INTEGER NOT NULL CHECK(end_codepoint>start_codepoint),
 source_sha256 TEXT NOT NULL, captured_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,evidence_id),
 UNIQUE(scope_id,memory_id,source_message_id,source_revision,start_codepoint,end_codepoint),
 FOREIGN KEY(scope_id,memory_id) REFERENCES memories(scope_id,memory_id) ON DELETE CASCADE,
 FOREIGN KEY(scope_id,source_message_id) REFERENCES messages(scope_id,message_id)
);
CREATE INDEX IF NOT EXISTS evidence_reverse ON evidence(scope_id,source_message_id,source_revision);
CREATE TABLE IF NOT EXISTS dependencies (
 scope_id TEXT NOT NULL, dependency_id TEXT NOT NULL,
 parent_kind TEXT NOT NULL CHECK(parent_kind IN ('message','memory')),
 parent_id TEXT NOT NULL, child_kind TEXT NOT NULL CHECK(child_kind IN ('memory','working_summary','alias','job','scheduled_task')),
 child_id TEXT NOT NULL, source_revision INTEGER, created_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,dependency_id),
 UNIQUE(scope_id,parent_kind,parent_id,child_kind,child_id),
 FOREIGN KEY(scope_id) REFERENCES scopes(scope_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS dependencies_reverse ON dependencies(scope_id,parent_kind,parent_id);
CREATE INDEX IF NOT EXISTS dependencies_child ON dependencies(scope_id,child_kind,child_id);
CREATE TABLE IF NOT EXISTS memory_history (
 scope_id TEXT NOT NULL, memory_id TEXT NOT NULL, version INTEGER NOT NULL,
 snapshot_json TEXT NOT NULL, expires_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,memory_id,version),
 FOREIGN KEY(scope_id,memory_id) REFERENCES memories(scope_id,memory_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS history_expiry ON memory_history(scope_id,expires_at);
CREATE TABLE IF NOT EXISTS memory_conflicts (
 scope_id TEXT NOT NULL, conflict_id TEXT NOT NULL, memory_id TEXT NOT NULL,
 target_version INTEGER NOT NULL CHECK(target_version>0), operation TEXT NOT NULL,
 candidate_json TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending'
   CHECK(state IN ('pending','resolved','dismissed')),
 resolution TEXT, resolved_at INTEGER,
 created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,conflict_id),
 FOREIGN KEY(scope_id,memory_id) REFERENCES memories(scope_id,memory_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS conflicts_pending
 ON memory_conflicts(scope_id,state,created_at);
CREATE TABLE IF NOT EXISTS memory_relations (
 scope_id TEXT NOT NULL, relation_id TEXT NOT NULL,
 source_memory_id TEXT NOT NULL, target_memory_id TEXT NOT NULL,
 relation_type TEXT NOT NULL CHECK(relation_type IN ('SUPPORTS','ELABORATES')),
 target_version INTEGER NOT NULL CHECK(target_version>0),
 created_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,relation_id),
 UNIQUE(scope_id,source_memory_id,target_memory_id,relation_type),
 FOREIGN KEY(scope_id,source_memory_id) REFERENCES memories(scope_id,memory_id) ON DELETE CASCADE,
 FOREIGN KEY(scope_id,target_memory_id) REFERENCES memories(scope_id,memory_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS relations_target
 ON memory_relations(scope_id,target_memory_id,relation_type);
CREATE TABLE IF NOT EXISTS jobs (
 scope_id TEXT NOT NULL, job_id TEXT NOT NULL, session_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('extract','summary')),
 idempotency_key TEXT NOT NULL, envelope_json TEXT NOT NULL,
 state TEXT NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','leased','saved','failed','cancelled')),
 attempts INTEGER NOT NULL DEFAULT 0, max_attempts INTEGER NOT NULL DEFAULT 3,
 lease_owner TEXT, lease_until INTEGER, next_attempt_at INTEGER NOT NULL,
 error_code TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,job_id), UNIQUE(scope_id,idempotency_key),
 FOREIGN KEY(scope_id,session_id) REFERENCES sessions(scope_id,session_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS jobs_ready ON jobs(state,next_attempt_at,lease_until);
CREATE UNIQUE INDEX IF NOT EXISTS one_summary_lease ON jobs(scope_id,session_id) WHERE kind='summary' AND state='leased';
CREATE TABLE IF NOT EXISTS coverage (
 scope_id TEXT NOT NULL, session_id TEXT NOT NULL, message_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('extract','summary')),
 source_revision INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN ('done','skipped','gap')),
 job_id TEXT, reason TEXT, updated_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,message_id,kind),
 FOREIGN KEY(scope_id,message_id) REFERENCES messages(scope_id,message_id) ON DELETE CASCADE,
 FOREIGN KEY(scope_id,session_id) REFERENCES sessions(scope_id,session_id)
);
CREATE TABLE IF NOT EXISTS outbox (
 scope_id TEXT NOT NULL, event_id TEXT NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('index_sync','scope_resync')),
 target_id TEXT NOT NULL, target_version INTEGER NOT NULL, deletion_epoch INTEGER NOT NULL,
 state TEXT NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','leased','failed')),
 attempts INTEGER NOT NULL DEFAULT 0, lease_owner TEXT, lease_until INTEGER,
 next_attempt_at INTEGER NOT NULL, error_code TEXT, created_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,event_id), UNIQUE(scope_id,kind,target_id),
 FOREIGN KEY(scope_id) REFERENCES scopes(scope_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS outbox_ready ON outbox(state,next_attempt_at,lease_until);
CREATE TABLE IF NOT EXISTS tombstones (
 scope_id TEXT NOT NULL, tombstone_id TEXT NOT NULL,
 target_kind TEXT NOT NULL CHECK(target_kind IN ('fact','message','field')),
 target_key TEXT NOT NULL, deletion_epoch INTEGER NOT NULL,
 source_cutoff_json TEXT NOT NULL, mode TEXT NOT NULL CHECK(mode IN ('old_sources','deny_field')),
 operation_id TEXT NOT NULL, created_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,tombstone_id), UNIQUE(scope_id,target_kind,target_key),
 FOREIGN KEY(scope_id) REFERENCES scopes(scope_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS tombstones_check ON tombstones(scope_id,target_kind,target_key);
CREATE TABLE IF NOT EXISTS capacity_ledger (
 scope_id TEXT NOT NULL, resource TEXT NOT NULL, used_bytes INTEGER NOT NULL CHECK(used_bytes>=0),
 used_records INTEGER NOT NULL CHECK(used_records>=0), reservation_bytes INTEGER NOT NULL DEFAULT 0,
 accounting_version INTEGER NOT NULL DEFAULT 1, last_tx_id TEXT NOT NULL,
 PRIMARY KEY(scope_id,resource), FOREIGN KEY(scope_id) REFERENCES scopes(scope_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS audit_events (
 scope_id TEXT NOT NULL, audit_id TEXT NOT NULL, operation_id TEXT NOT NULL,
 action TEXT NOT NULL, target_id TEXT, from_version INTEGER, to_version INTEGER,
 result_code TEXT NOT NULL, deletion_epoch INTEGER NOT NULL,
 created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,audit_id), FOREIGN KEY(scope_id) REFERENCES scopes(scope_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS audit_expiry ON audit_events(scope_id,expires_at);
CREATE TABLE IF NOT EXISTS scheduled_tasks (
 scope_id TEXT NOT NULL, task_id TEXT NOT NULL, memory_id TEXT,
 task_version INTEGER NOT NULL DEFAULT 1, authorization_message_id TEXT NOT NULL,
 due_at INTEGER NOT NULL, timezone TEXT NOT NULL, recurrence_json TEXT NOT NULL DEFAULT '{}',
 state TEXT NOT NULL CHECK(state IN ('pending','scheduled','schedule_failed','cancel_pending','cancelled','delivery_failed','delivered')),
 external_id TEXT, idempotency_key TEXT NOT NULL, deletion_epoch INTEGER NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL, lease_until INTEGER,
 last_error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,task_id), UNIQUE(scope_id,idempotency_key),
 FOREIGN KEY(scope_id,memory_id) REFERENCES memories(scope_id,memory_id),
 FOREIGN KEY(scope_id,authorization_message_id) REFERENCES messages(scope_id,message_id)
);
CREATE INDEX IF NOT EXISTS scheduled_ready ON scheduled_tasks(state,next_attempt_at,due_at);
CREATE TABLE IF NOT EXISTS operations (
 scope_id TEXT NOT NULL, operation_id TEXT NOT NULL, command_seq INTEGER NOT NULL,
 request_digest TEXT NOT NULL, result_json TEXT NOT NULL, created_at INTEGER NOT NULL,
 PRIMARY KEY(scope_id,operation_id), UNIQUE(scope_id,command_seq),
 FOREIGN KEY(scope_id) REFERENCES scopes(scope_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS migration_runs (
 run_id TEXT PRIMARY KEY NOT NULL, source_sha256 TEXT NOT NULL, mapping_sha256 TEXT NOT NULL,
 generation INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN ('pending','committed','failed')),
 created_at INTEGER NOT NULL, UNIQUE(source_sha256,mapping_sha256,generation)
);
CREATE TABLE IF NOT EXISTS migration_items (
 run_id TEXT NOT NULL, item_key TEXT NOT NULL, scope_id TEXT,
 state TEXT NOT NULL CHECK(state IN ('imported','quarantined','expired','blocked')),
 reason TEXT, target_id TEXT, source_received_at INTEGER,
 PRIMARY KEY(run_id,item_key), FOREIGN KEY(run_id) REFERENCES migration_runs(run_id) ON DELETE CASCADE,
 FOREIGN KEY(scope_id) REFERENCES scopes(scope_id)
);
CREATE TABLE IF NOT EXISTS persona_migration_audit (
 source_scope_id TEXT NOT NULL, target_scope_id TEXT NOT NULL,
 contact_id TEXT NOT NULL, source_persona_id TEXT NOT NULL,
 target_persona_id TEXT NOT NULL, source_memory_id TEXT NOT NULL,
 target_memory_id TEXT NOT NULL, source_version INTEGER NOT NULL,
 target_version INTEGER NOT NULL DEFAULT 1, target_fact_key TEXT,
 state TEXT NOT NULL DEFAULT 'pending'
   CHECK(state IN ('pending','committed','failed','rolled_back')),
 relation_summary_json TEXT NOT NULL DEFAULT '{}',
 last_error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
 committed_at INTEGER, rolled_back_at INTEGER,
 PRIMARY KEY(source_scope_id,source_memory_id,target_scope_id),
 UNIQUE(target_scope_id,target_memory_id)
);
CREATE INDEX IF NOT EXISTS persona_migration_audit_target
 ON persona_migration_audit(target_scope_id,state,updated_at);
