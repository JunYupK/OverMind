# Cross-LLM Canonical Memory Service — Architecture Baseline v0.1

## 0. Instructions for this brainstorming session

I have already completed an extensive architecture brainstorming process for this project.

Treat the architecture decisions in this document as the **approved Architecture Baseline v0.1**.

Do **not** redesign or replace these decisions merely because another implementation may be cleaner or more sophisticated.

The purpose of this Superpowers brainstorming session is to:

1. Validate that the approved architecture is implementable.
2. Identify missing implementation-level requirements.
3. Design the **Harness Engineering** strategy.
4. Design the **Loop Engineering / evaluation-feedback loops**.
5. Define repository structure, engineering guardrails, automated invariants, testing strategy, and agent workflow.
6. Prepare the design for `writing-plans` and later subagent-driven implementation.
7. Surface genuine contradictions only if they make the approved architecture impossible or internally inconsistent.

Do not prematurely introduce infrastructure such as Kafka, Kubernetes, microservices, distributed tracing, or multi-tenancy unless an approved Post-MVP upgrade trigger requires it.

For every major implementation decision, preserve:

* Current MVP Decision
* Rationale / Trade-off
* Deferred Alternatives
* Post-MVP Upgrade Trigger
* Expected Evolution Path

The project is intended to continue beyond the MVP, so deferred alternatives must remain visible in the architecture history rather than being discarded.

---

# 1. Problem

I actively use multiple AI providers, especially Claude Chat and ChatGPT Chat.

Each provider maintains independent conversation context and memory.

This creates memory fragmentation and staleness.

Example:

* Claude may know my current learning priority is CKAD.
* ChatGPT may still believe an older Kafka-related priority is current.

The goal is to create a provider-neutral memory layer so that multiple AI clients operate against the same latest user context.

---

# 2. Product Vision

Build a:

**Cross-LLM Canonical Memory Layer**

rather than merely a vector-search/RAG system.

Conceptually:

Claude / ChatGPT / coding agents
↓
Canonical Memory
↓
single provider-neutral source of truth

Providers do not synchronize memory directly with each other.

Instead:

Claude ↔ Canonical Memory ↔ ChatGPT

Cross-provider synchronization is therefore implemented as **shared canonical read/write**, not provider-to-provider replication.

---

# 3. MVP Scope

## Included

* Single user
* Multiple AI clients/providers
* Provider-neutral Canonical Memory
* Memory extraction
* Canonicalization
* Temporal semantics
* Conflict handling
* Selective retrieval
* Current-state snapshots
* Historical retrieval
* Bootstrap from existing conversation history
* MCP integration
* Privacy/forget semantics
* Evaluation and observability

## Explicitly excluded from MVP

* Multi-user SaaS
* Multi-tenancy
* Complex RBAC
* Billing/quota
* Kafka
* Kubernetes
* Microservices
* Elasticsearch/OpenSearch
* Automatic native-provider-memory synchronization
* Large-scale traffic infrastructure
* Perfect automatic memory classification

Design principle:

> Build for one user, but preserve domain boundaries so that one user → many users does not require rewriting the core domain.

---

# 4. Core Architecture Principle

Separate:

**Observation**
from
**Canonical Memory**

An LLM must never directly overwrite Canonical Memory.

LLMs perform semantic reasoning.

The application owns truth/state transitions.

Pipeline:

Raw input
→ Memory extraction
→ Structured Observation
→ Candidate lookup
→ Relation classification
→ Deterministic resolution
→ Canonical Memory
→ Snapshot projection

---

# 5. Subject Model

Memory Subject answers:

> Who or what is this memory about?

MVP subjects:

* USER
* PROJECT

PROJECT is not a Memory Type.

Possible Post-MVP subjects:

* PERSON
* ORGANIZATION
* REPOSITORY
* SERVICE

Upgrade only when real memory requirements for those entities appear.

---

# 6. Memory Types

MVP Memory Types:

* PROFILE
* PREFERENCE
* GOAL
* STATE
* DECISION
* EVENT

Meaning:

PROFILE
Relatively persistent factual property.

PREFERENCE
Preference affecting future responses or choices.

GOAL
Desired future state.

STATE
Observed current situation/progress.

DECISION
Chosen option that should constrain future work.

EVENT
Historical occurrence, experience, achievement, etc.

Do not introduce unnecessary PLAN / EXPERIENCE / ACHIEVEMENT type proliferation in the MVP.

---

# 7. Memory Slot Model

A Slot identifies the semantic property.

Examples:

* profile.role
* profile.experience_level
* learning.primary_focus
* career.primary_goal
* preference.response_style
* technical.interest
* project.active
* project.stage

Slots have cardinality:

* SINGLE
* MULTI

Use a **Hybrid Slot Registry**.

## Registered Slot

Used for common/core semantic properties.

Has deterministic constraints/cardinality.

## Dynamic Slot

Used for long-tail memory.

Generated conservatively when no registered slot exists.

## Evolution

Dynamic slots may later be promoted to registered slots.

MVP promotion may be manual.

Post-MVP automatic clustering/promotion is deferred until dynamic-slot fragmentation becomes a real problem.

---

# 8. Temporal Semantics

Temporal semantics are independent from Memory Type.

Use:

* PERSISTENT
* ACTIVE_STATE
* TIME_BOUNDED
* EPISODIC

PERSISTENT
Valid until changed/contradicted.

ACTIVE_STATE
Current ongoing situation until state transition.

TIME_BOUNDED
Current only during a defined time/event window.

EPISODIC
Historical occurrence.

Important:

> Memory existence is not the same as current validity.

CURRENT/HISTORICAL should normally be computed during retrieval rather than stored as lifecycle states.

---

# 9. Record Lifecycle

Use:

* ACTIVE
* SUPERSEDED
* RETRACTED

ACTIVE
Valid canonical representation.

SUPERSEDED
Replaced by a better/new canonical representation.

RETRACTED
Originally incorrect information that has been explicitly corrected.

A memory with `valid_until < now` may remain lifecycle ACTIVE because it can still be a valid historical fact.

---

# 10. Goal-specific State

GOAL only:

* PURSUING
* PAUSED
* ACHIEVED
* ABANDONED

Do not mix Goal State with Record Lifecycle.

---

# 11. Memory Relations

Classify relationships rather than only conflict/non-conflict.

Use:

* DUPLICATE
* COMPATIBLE
* REFINEMENT
* TEMPORAL_SUCCESSION
* CORRECTION
* CONTRADICTION

Examples:

DUPLICATE
Same fact, different wording.

TEMPORAL_SUCCESSION
Normal change over time.

CORRECTION
Old statement was wrong from the beginning.

CONTRADICTION
Mutually exclusive claims for the same semantic property/time.

Application code owns lifecycle/temporal mutation after semantic classification.

---

# 12. Canonical Representation

Canonical Memory should primarily contain a minimal structured fact.

Example:

slot:
learning.primary_focus

structured value:
CKAD

Derived natural-language representation may be:

"Current primary learning focus: CKAD."

The structured value is authoritative.

Canonical text and embedding are derived representations.

---

# 13. Evidence / Provenance

Multiple observations may support one Canonical Memory.

Example:

Observation A ─┐
Observation B ─┼─→ Canonical Memory
Observation C ─┘

Trust should not be ranked globally by provider.

Prefer evidence origin:

USER_EXPLICIT

>

USER_IMPLICIT

>

MODEL_INFERRED

Model-inferred durable memory writes should be conservative.

---

# 14. Read Intent

Use:

* NONE
* CURRENT
* HISTORICAL

NONE
Personal context is unnecessary.

CURRENT
Current user/project context affects the answer.

HISTORICAL
Specific historical memory/event/decision is needed.

This is a retrieval strategy hierarchy, not a network waterfall.

Avoid:

snapshot → scope → vector → raw

serial MCP calls.

Desired behavior:

NONE
→ zero memory call

CURRENT
→ one context call

HISTORICAL
→ one historical recall call

---

# 15. Snapshot Model

Snapshot is a **read projection**, not truth.

Two levels:

## Core Snapshot

Very small, frequently reusable current context.

## Scoped Snapshot

Domain-specific context such as:

* learning
* career
* project
* etc.

For CURRENT retrieval:

one request should return:

Core + relevant scope

rather than requiring multiple MCP round trips.

Snapshot must be deterministic and rebuildable from Canonical Memory.

Version snapshots for debugging.

---

# 16. Historical Retrieval

Use hybrid retrieval.

Principle:

> Structure determines what can be true; semantics determines what is relevant.

Historical retrieval:

Structured prefilter
+
PostgreSQL full-text search
+
pgvector semantic search
→ fusion/ranking

Hard constraints should only use high-confidence information.

Examples:

* subject
* lifecycle
* explicit temporal requirement

Predicted slot/type/domain should normally be soft boosts rather than hard filters.

Raw conversation is fallback only when Canonical Memory cannot sufficiently answer the historical query.

---

# 17. Context Pack

Use:

**D. Adaptive Context Pack**

with:

**Adaptive Token Budget**

Process:

retrieve sufficiently broad candidates
→ rank
→ pack based on query-specific token budget

Normal context contains:

* Canonical Fact
* minimal provenance metadata

Minimal metadata is always included.

Examples:

* provider/source
* observed_at

Raw evidence is not included by default.

Include evidence when:

* user asks for verification/source
* conflicting evidence exists
* canonical fact requires validation
* debugging/audit context requires it

Confidence should normally be hidden.

Expose uncertainty only when there is a meaningful problem.

Unresolved conflict must be surfaced explicitly rather than silently choosing a fact.

Separate:

**Model Context Projection**
from
**Admin/Debug Projection**

Primary optimization target:

> answer-impact per token

---

# 18. MCP Contract

Expose only three intent-level tools:

* recall_memory
* remember_memory
* forget_memory

Do not expose internal operations such as:

* supersede_memory
* retract_memory
* vector_search
* snapshot_lookup
* conflict_resolution

Those remain server responsibilities.

---

# 19. Write Policy

Priority:

1. EXPLICIT_SUPPRESS
2. EXPLICIT_SAVE
3. IMPLICIT_SAVE

EXPLICIT_SUPPRESS:

User says not to remember/store something.

→ do not persist it.

EXPLICIT_SAVE:

User explicitly asks to remember/save.

→ must enter durable persistence pipeline.

IMPLICIT_SAVE:

AI judges information to be durable and likely to materially affect future conversations.

Future-answer impact is the main criterion.

Avoid storing transient chatter, weak speculation, secrets, credentials, etc.

---

# 20. Observation Durability

`remember_memory()` success guarantees:

> Observation has been durably persisted.

It does not necessarily mean Canonical Memory has already been updated.

Possible logical responses:

ACCEPTED / RESOLVED

or

ACCEPTED / PENDING

Normal canonicalization does not mutate Observation.

Observation is immutable except for privacy FORGET deletion.

---

# 21. Write Routing

Use:

**Hybrid Fast Path + Async Consolidation**

Fast Path exists for freshness.

Async exists for semantic correctness/convergence.

## Fast Path

Use when immediate canonicalization is safe.

Signals include:

* Registered Slot
* clear/direct value
* user assertion is sufficiently explicit
* deterministic relation to existing memory
* localized/small mutation scope

## Async-required conditions

Any strong Async-required condition overrides Fast eligibility.

Examples:

* Dynamic Slot
* semantic ambiguity
* soft/hard conflict
* correction vs temporal succession unclear
* multiple Canonical Memories affected
* complex semantic reconciliation needed

Rule:

if async_required:
ASYNC
else if fast_eligible:
FAST
else:
ASYNC

EXPLICIT_SAVE does not automatically imply FAST.

---

# 22. Async Processing State

Use a simple technical state machine:

* PENDING
* PROCESSING
* RESOLVED
* FAILED

Retry:

FAILED
→ PENDING

Do not encode semantic meanings such as CONFLICT or CORRECTION into processing status.

Processing status answers:

> Has the system completed the work?

Resolution answers:

> What did the memory mean?

Therefore this is valid:

Job = RESOLVED
Resolution = UNRESOLVED_CONFLICT

---

# 23. Async Execution

MVP:

**PostgreSQL DB Polling / Job Table**

No broker.

Observation/work item is durably persisted.

Worker polls pending jobs.

Expected evolution:

DB Job Queue
→ Transactional Outbox
→ Queue/Broker
→ Kafka only if justified

Upgrade triggers include:

* multiple independent consumers
* substantially increased throughput
* stronger delivery isolation
* independently scaled workers/services
* event-driven consumers become a genuine requirement

Do not add Kafka merely because it is technically interesting.

---

# 24. Privacy Semantics

Distinguish:

## SUPPRESS

"This should not be remembered."

→ never persist.

## RETRACT

"That information was wrong."

→ correctness operation.

Keep historical provenance.

## FORGET

"Forget/delete that information."

→ privacy deletion.

Use:

**Internal Full Purge**

FORGET should remove relevant data from Canonical Memory Service:

* Canonical Memory
* related Observations/Evidence
* embeddings/search representation
* Snapshot/cache
* relations/resolution data
* pending jobs
* related Raw Archive data

Deletion audit may preserve only minimal metadata:

* request timestamp
* completion timestamp
* result
* deletion counts

Never store the deleted content itself in the deletion audit.

Native Claude/ChatGPT conversation deletion is outside the MVP guarantee.

---

# 25. Live Ingestion

Use:

**Hybrid Client-driven ingestion**

Where supported:

conversation
→ client-side Skill/Memory Policy
→ remember_memory
→ Canonical Memory

Provider capability differences must stay inside adapters.

Core domain must remain provider-neutral.

---

# 26. Bootstrap Ingestion

Cold start is explicitly undesirable.

Therefore choose:

**Full existing-conversation import**

Import available Claude/ChatGPT conversation/export history.

Pipeline:

Provider Export
→ Provider-specific Parser
→ Normalized Conversation
→ Conversation-aware Chunking
→ Memory Extraction
→ Observation
→ Async Canonicalization
→ Canonical Memory
→ Snapshot Build

Bootstrap is an offline reconstruction of canonical state from noisy historical observations.

It must actively use:

* deduplication
* temporal reconstruction
* relation classification
* conflict handling

---

# 27. Conversation-aware Chunking

Conversation is the highest context boundary.

Never cross conversation boundaries when chunking.

Inside each conversation, use:

* token budget
* user/assistant turn continuity
* obvious topic shift
* optionally small overlap

Do not fix exact token or overlap numbers during architecture design.

Tune them later using real Claude/ChatGPT export samples.

Chunk is used both for:

* bootstrap extraction
* optional raw historical fallback retrieval

---

# 28. Raw Conversation Archive

Canonical Memory is the Primary Store.

Raw Conversation Archive is:

**bounded / optional evidence storage**

Use only for:

* bootstrap reprocessing
* historical fallback when canonical retrieval is insufficient

Normal CURRENT/HISTORICAL retrieval prefers Canonical Memory.

Live conversations should not automatically be fully duplicated into the archive.

Bootstrap raw may be retained during an initial stabilization/reprocessing period.

Permanent retention is not the default.

Raw retrieval result is:

NON_CANONICAL_EVIDENCE

Finding a fact in raw history must not automatically promote it into Canonical Memory.

FORGET must also purge related raw archive data.

---

# 29. Native Provider Memory

MVP:

Canonical Memory has higher authority than native provider memory.

Native memory may remain enabled.

Do not attempt automatic bidirectional native-memory synchronization in MVP.

Effective priority:

Current explicit user statement

>

Canonical Memory

>

Native provider memory

If provider APIs later expose reliable native-memory control, synchronization may be reconsidered.

---

# 30. Security / Deployment

MVP network:

**Public MCP/API + Private DB**

Externally expose only necessary HTTPS endpoints.

Authentication:

**OAuth 2.1 / OIDC**

Use an existing/managed authorization provider.

Do not build an authorization server.

Scopes:

* memory:read
* memory:write
* memory:delete

MVP user model:

single authenticated subject

No RBAC/multi-tenancy yet.

PostgreSQL + pgvector:

private only

Data protection:

* TLS in transit
* infrastructure/volume encryption at rest
* encrypted backup
* deployment-managed secrets

Application-level field encryption is deferred.

Logs must contain metadata only.

Never log:

* raw memory payload
* conversation content
* canonical values
* OAuth token
* Authorization header

IP allowlisting is defense-in-depth only, not identity/authentication.

Deployment:

single application + PostgreSQL

No Kubernetes.

No microservices.

---

# 31. Evaluation

Use:

**Golden Set + Runtime Metrics**

Golden Set categories:

* CURRENT
* HISTORICAL
* NONE

Core product metrics:

1. Cross-provider consistency rate
2. Canonical memory correctness
3. Retrieval task success rate

NONE cases must verify that unnecessary memory calls are not made.

Example:

Question:
"Explain Java HashMap."

Expected:
recall_memory should not be called merely because user memory exists.

---

# 32. Observability

MVP runtime metrics:

* recall latency
* remember latency
* Fast/Async ratio
* canonicalization success/failure
* retry count
* pending job count
* oldest pending age
* raw fallback rate
* average/context token usage

Use basic:

Spring Boot metrics
→ Prometheus
→ Grafana

No distributed tracing infrastructure in MVP.

Logging remains metadata-only.

---

# 33. Core Domain Invariants

Treat these as architectural invariants:

1. Canonical Memory is the canonical truth.
2. Observation is evidence and cannot directly dictate canonical state.
3. Observation is immutable during normal processing; FORGET is the privacy exception.
4. CURRENT/HISTORICAL are computed from temporal validity, not stored as one lifecycle status.
5. Record Lifecycle, Temporal Semantics, and Goal State are independent dimensions.
6. A SINGLE Slot must not contain mutually-exclusive overlapping current truths.
7. Snapshot and Context Pack are rebuildable projections, never authoritative truth.
8. Unresolved ambiguity must not be forced into a false canonical truth.

---

# 34. Component Boundaries

Expected major components:

## Adapter Layer

* MCP Adapter
* HTTP/Admin Adapter
* Bootstrap Import Adapter
* Provider-specific export parsers
* OAuth integration

## Application Layer

* RecallMemoryService
* RememberMemoryService
* ForgetMemoryService
* BootstrapIngestionService

## Domain/Application Components

* WriteRouter
* Canonicalizer
* CandidateFinder
* SlotRegistry
* RelationClassifier
* ResolutionEngine
* TemporalPolicy
* RetrievalEngine
* SnapshotProjector
* ContextPackBuilder
* PrivacyPurger

## Infrastructure

* PostgreSQL
* pgvector
* PostgreSQL FTS
* DB Job Queue
* DB Polling Worker
* embedding provider
* semantic LLM provider
* metrics/logging

Provider-specific concepts must not leak into the core domain.

---

# 35. Conceptual Persistence Model

Expected conceptual entities/tables include:

* memory_subject
* memory_slot
* observation
* canonical_memory
* memory_evidence
* canonicalization_job
* canonicalization_resolution
* memory_embedding
* snapshot
* raw_conversation
* conversation_chunk
* bootstrap_import
* deletion_audit

Canonical structured value may use JSONB where appropriate.

Embedding must be treated as a derived representation, not truth.

---

# 36. Post-MVP Evolution Registry

Do not erase these deferred paths.

## Async

MVP:
DB Job Queue

Evolution:
Outbox → Broker → Kafka

Trigger:
throughput, consumers, isolation, independent scaling

## Users

MVP:
single user

Evolution:
multi-user / tenants

Trigger:
actual external users

## Authorization

MVP:
OAuth scopes

Evolution:
RBAC / ABAC

Trigger:
multiple roles/organizations

## Deployment

MVP:
monolith

Evolution:
worker/service separation → possibly Kubernetes

Trigger:
independent scaling / HA requirements

## Retrieval

MVP:
PostgreSQL FTS + pgvector

Evolution:
Elasticsearch/OpenSearch/vector service

Trigger:
measured retrieval scale or latency problem

## Slots

MVP:
Hybrid Slot Registry

Evolution:
automatic clustering/promotion

Trigger:
significant dynamic-slot fragmentation

## Relations

MVP:
resolution metadata

Evolution:
first-class relation graph

Trigger:
relation traversal becomes part of retrieval/reasoning

## Conflicts

MVP:
conservative unresolved conflict

Evolution:
Conflict/Review domain

Trigger:
meaningful operational volume of unresolved conflicts

## Raw Archive

MVP:
bounded/optional

Evolution:
tiered retention

Trigger:
historical recall demand justifies storage/privacy cost

## Security

MVP:
infrastructure encryption

Evolution:
application-level/field encryption

Trigger:
stronger threat model or external service requirements

## Observability

MVP:
metrics + structured metadata logs

Evolution:
OpenTelemetry / distributed tracing

Trigger:
distributed architecture

## Native Provider Memory

MVP:
Canonical priority, coexistence

Evolution:
native-memory synchronization

Trigger:
reliable provider APIs become available

---

# 37. What I Want Superpowers to Brainstorm Next

Do not reopen the architecture above unless a genuine contradiction is found.

Focus next on **Harness Engineering**.

Please help design:

1. Repository structure
2. Project instruction files such as AGENTS.md / CLAUDE.md equivalents
3. Which architecture/ADR/spec files agents must read
4. Build/test commands
5. Local development environment
6. PostgreSQL/pgvector integration-test environment
7. Test fixture strategy
8. Architecture dependency rules
9. Privacy/logging guardrails
10. Database migration guardrails
11. Secret-management guardrails
12. Agent permissions and prohibited destructive operations
13. CI gates
14. Automated checks for architectural invariants
15. Context strategy for implementation/reviewer subagents

Then design **Loop Engineering**.

For each implementation task, determine the feedback loop:

Spec
→ implementation
→ compile/static verification
→ unit tests
→ integration tests
→ architecture/invariant checks
→ spec compliance review
→ code-quality review
→ fix
→ scoped re-test
→ re-review
→ completion

Define which failures should automatically return work to the implementation agent.

Particularly important project-specific automated evaluators include:

### Canonical invariant evaluator

Fail if a SINGLE slot can produce mutually-exclusive overlapping current truths.

### Privacy evaluator

After FORGET, fail if relevant:

* Observation
* Canonical Memory
* embedding
* raw chunk
* snapshot
* relation/job

remains retrievable.

### Architecture evaluator

Fail if provider-specific Claude/ChatGPT concepts leak into the core domain.

### Logging evaluator

Fail if memory payload, raw conversation content, canonical value, tokens, or authorization headers can appear in application logs.

### Observation evaluator

Fail if ordinary canonicalization mutates historical Observation data.

### Projection evaluator

Verify Snapshot can be rebuilt solely from Canonical Memory.

### Routing evaluator

Verify Async-required conditions always override Fast eligibility.

### Retrieval evaluator

Verify NONE queries can operate without unnecessary memory retrieval.

---

# 38. Desired Output From This Brainstorming Session

Please produce, through normal Superpowers brainstorming rather than jumping directly into code:

1. Harness Engineering design
2. Loop Engineering design
3. Repository/document structure
4. Testing pyramid and test boundaries
5. Automated architectural invariant strategy
6. Agent/subagent context and permission model
7. CI/local feedback-loop design
8. Any missing implementation-level requirement that does not require redesigning the approved architecture
9. Updated Post-MVP evolution triggers if Harness/Loop design introduces additional deferred options

After I approve that design, proceed to the appropriate Superpowers planning workflow.

Do not begin implementation before the brainstorming/design is approved.
