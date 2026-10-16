# SmartOffice Rebuild Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild SmartOffice into a runnable interview-ready meeting-room booking system while preserving the recovered Vue frontend contract.

**Architecture:** Keep the recovered Vue 3 frontend at repository root and create an independent Spring Boot backend under `backend/`. The backend uses stateless JWT authentication, MyBatis-Plus persistence, a Redisson Bloom Filter for early room-ID rejection, and a room-scoped Redisson lock whose critical section rechecks room state and overlapping bookings before insert.

**Tech Stack:** Java 17, Spring Boot 3.x, Spring Security 6, MyBatis-Plus, MySQL 8, Redis 7, Redisson, JJWT, Knife4j, JUnit 5/Mockito, Vue 3/TypeScript/Vite/Element Plus.

**Spec:** `docs/superpowers/specs/2026-08-22-smartoffice-rebuild-design.md`

## Global Constraints

- Backend package is exactly `com.geek.smartoffice`.
- Backend port is exactly `8081`.
- Existing frontend contracts `POST /users/login`, `GET /rooms/list`, and `POST /orders/book` remain compatible.
- JWT authentication is stateless and passwords use BCrypt.
- Room booking conflict rule is `newStart < oldEnd && newEnd > oldStart`.
- Redisson lock key is `smartoffice:booking:room:{roomId}`.
- Bloom Filter key is `smartoffice:room:id:bloom`, expected insertions `10000`, false-positive rate `0.01`.
- Do not add email, custom rate limiting, custom idempotency, WebSocket, MQ, or order-timeout compensation in this rebuild.
- Production secrets are supplied by environment variables; no production credential is committed.

---

## File Structure

### Backend bootstrap/config

- `backend/pom.xml` — Maven dependency and plugin definitions.
- `backend/src/main/java/com/geek/smartoffice/SmartOfficeApplication.java` — application entry point.
- `backend/src/main/resources/application.yml` — default configuration with env substitutions.
- `backend/src/main/resources/application-test.yml` — test overrides.
- `backend/src/main/resources/schema.sql` — database schema.
- `backend/src/main/resources/data.sql` — deterministic demo data, including BCrypt demo users.

### Common/security

- `backend/src/main/java/com/geek/smartoffice/common/result/Result.java` — API envelope.
- `backend/src/main/java/com/geek/smartoffice/common/exception/BusinessException.java` — status-aware business exception.
- `backend/src/main/java/com/geek/smartoffice/common/exception/GlobalExceptionHandler.java` — JSON exception mapping.
- `backend/src/main/java/com/geek/smartoffice/common/security/JwtProperties.java` — JWT config binding.
- `backend/src/main/java/com/geek/smartoffice/common/security/JwtService.java` — token creation and parsing.
- `backend/src/main/java/com/geek/smartoffice/common/security/JwtAuthenticationFilter.java` — bearer-token authentication.
- `backend/src/main/java/com/geek/smartoffice/common/security/JsonAuthenticationEntryPoint.java` — JSON 401.
- `backend/src/main/java/com/geek/smartoffice/common/security/JsonAccessDeniedHandler.java` — JSON 403.
- `backend/src/main/java/com/geek/smartoffice/config/SecurityConfig.java` — stateless authorization rules.
- `backend/src/main/java/com/geek/smartoffice/config/CorsConfig.java` — local development CORS.
- `backend/src/main/java/com/geek/smartoffice/config/RedisConfig.java` — RedisTemplate JSR-310 serialization.

### Domain/model

- `backend/src/main/java/com/geek/smartoffice/model/entity/SysUser.java`
- `backend/src/main/java/com/geek/smartoffice/model/entity/MeetingRoom.java`
- `backend/src/main/java/com/geek/smartoffice/model/entity/BookingOrder.java`
- `backend/src/main/java/com/geek/smartoffice/model/dto/LoginDTO.java`
- `backend/src/main/java/com/geek/smartoffice/model/dto/RoomSaveDTO.java`
- `backend/src/main/java/com/geek/smartoffice/model/dto/BookingDTO.java`
- `backend/src/main/java/com/geek/smartoffice/model/vo/RoomVO.java`
- `backend/src/main/java/com/geek/smartoffice/model/vo/BookingVO.java`
- `backend/src/main/java/com/geek/smartoffice/common/enums/UserRole.java`
- `backend/src/main/java/com/geek/smartoffice/common/enums/RoomStatus.java`
- `backend/src/main/java/com/geek/smartoffice/common/enums/BookingStatus.java`

### Persistence/service/controller

- `backend/src/main/java/com/geek/smartoffice/mapper/SysUserMapper.java`
- `backend/src/main/java/com/geek/smartoffice/mapper/MeetingRoomMapper.java`
- `backend/src/main/java/com/geek/smartoffice/mapper/BookingOrderMapper.java`
- `backend/src/main/java/com/geek/smartoffice/service/SysUserService.java`
- `backend/src/main/java/com/geek/smartoffice/service/MeetingRoomService.java`
- `backend/src/main/java/com/geek/smartoffice/service/BookingService.java`
- `backend/src/main/java/com/geek/smartoffice/service/BookingWriteService.java` — transactional write boundary that commits before the outer lock is released.
- `backend/src/main/java/com/geek/smartoffice/service/impl/SysUserServiceImpl.java`
- `backend/src/main/java/com/geek/smartoffice/service/impl/MeetingRoomServiceImpl.java`
- `backend/src/main/java/com/geek/smartoffice/service/impl/BookingServiceImpl.java`
- `backend/src/main/java/com/geek/smartoffice/service/impl/BookingWriteServiceImpl.java`
- `backend/src/main/java/com/geek/smartoffice/controller/SysUserController.java`
- `backend/src/main/java/com/geek/smartoffice/controller/RoomController.java`
- `backend/src/main/java/com/geek/smartoffice/controller/OrderController.java`

### Bloom/AOP

- `backend/src/main/java/com/geek/smartoffice/service/RoomBloomFilterService.java`
- `backend/src/main/java/com/geek/smartoffice/service/impl/RoomBloomFilterServiceImpl.java`
- `backend/src/main/java/com/geek/smartoffice/config/RoomBloomFilterInitializer.java`
- `backend/src/main/java/com/geek/smartoffice/common/annotation/OperationLog.java`
- `backend/src/main/java/com/geek/smartoffice/common/aspect/OperationLogAspect.java`

### Tests

- `backend/src/test/java/com/geek/smartoffice/service/BookingTimeRuleTest.java`
- `backend/src/test/java/com/geek/smartoffice/service/BookingServiceImplTest.java`
- `backend/src/test/java/com/geek/smartoffice/common/security/JwtServiceTest.java`
- `backend/src/test/java/com/geek/smartoffice/controller/SysUserControllerTest.java`

### Deploy/docs/frontend adjustments

- `docker-compose.dev.yml` — MySQL/Redis local dependencies.
- `backend/Dockerfile` — backend image.
- `deploy/.env.example` — production variable template.
- `deploy/run-host.sh` — host-network deployment command.
- `.gitignore` — build/IDE/secrets exclusions.
- `.env.development` — local API URL `http://localhost:8081`.
- `.env.production.example` — production API URL template.
- `README.md` — complete runbook and interview traceability.

---

### Task 1: Backend skeleton, database schema, and API envelope

**Files:** Create `backend/pom.xml`, application entry/config resources, schema/data SQL, entities, enums, mappers, `Result`, `BusinessException`, `GlobalExceptionHandler`.

**Interfaces:**
- Produces `Result.success()`, `Result.success(T)`, `BusinessException(HttpStatus,String)`.
- Produces MyBatis mapper interfaces used by all later services.

- [ ] **Step 1: Write smoke tests for the response envelope and enum codes.**
- [ ] **Step 2: Run the backend test command and confirm the new tests fail before implementation.**
- [ ] **Step 3: Add Maven dependencies, application bootstrap, schema/data SQL, entities/enums/mappers, unified result and exception handler.**
- [ ] **Step 4: Run focused tests and confirm they pass.**
- [ ] **Step 5: Commit `feat: initialize SmartOffice backend foundation`.**

### Task 2: JWT login and Spring Security

**Files:** Create JWT properties/service/filter, JSON 401/403 handlers, security/CORS config, login DTO, user service implementation, user controller.

**Interfaces:**
- `String JwtService.createToken(Long userId, String username, UserRole role)`
- `AuthenticatedUser JwtService.parseToken(String token)`
- `SysUser SysUserService.login(String username, String rawPassword)`
- `POST /users/login -> Result<String>`

- [ ] **Step 1: Add tests proving a valid token round-trips claims, a malformed token is rejected, correct BCrypt credentials log in, and wrong credentials return 401.**
- [ ] **Step 2: Run focused tests and verify failure.**
- [ ] **Step 3: Implement JWT/BCrypt login and stateless SecurityFilterChain with JSON 401/403 responses.**
- [ ] **Step 4: Run the focused security/login tests.**
- [ ] **Step 5: Commit `feat: add jwt authentication and login`.**

### Task 3: Meeting-room query and administrator CRUD

**Files:** Create room DTO/VO, meeting-room service implementation, room controller.

**Interfaces:**
- `List<RoomVO> MeetingRoomService.listRooms()`
- `MeetingRoom MeetingRoomService.getExistingRoom(Long roomId)`
- `Long MeetingRoomService.createRoom(RoomSaveDTO dto)`
- `void MeetingRoomService.updateRoom(Long id, RoomSaveDTO dto)`
- `void MeetingRoomService.deleteRoom(Long id)`
- `GET /rooms/list` preserves recovered frontend JSON fields.

- [ ] **Step 1: Add service/controller tests for list mapping, missing room 404, and role-protected CRUD.**
- [ ] **Step 2: Verify tests fail.**
- [ ] **Step 3: Implement room mapping and CRUD with logical deletion and equipment JSON parsing.**
- [ ] **Step 4: Run room tests.**
- [ ] **Step 5: Commit `feat: add meeting room management`.**

### Task 4: Redisson room Bloom Filter

**Files:** Create Bloom Filter service/initializer and integrate room creation.

**Interfaces:**
- `boolean RoomBloomFilterService.mightContain(Long roomId)`
- `void RoomBloomFilterService.put(Long roomId)`
- `void RoomBloomFilterService.initialize(Collection<Long> roomIds)`

- [ ] **Step 1: Add tests proving a negative Bloom result short-circuits booking room lookup and create-room writes the ID into Bloom.**
- [ ] **Step 2: Verify focused tests fail.**
- [ ] **Step 3: Implement Redisson `RBloomFilter<Long>` with key `smartoffice:room:id:bloom`, capacity 10000 and false-positive rate 0.01; load active IDs at application-ready time.**
- [ ] **Step 4: Run Bloom-related tests.**
- [ ] **Step 5: Commit `feat: add room bloom filter`.**

### Task 5: Booking conflict rule and room-scoped Redisson lock

**Files:** Create booking DTO/VO, booking service implementation, order controller, operation status enum.

**Interfaces:**
- `void BookingService.book(BookingDTO dto, Long currentUserId)`
- `void BookingWriteService.createBookingInsideLock(BookingDTO dto, Long currentUserId)` is `@Transactional`; it must return only after commit so the outer service can unlock safely.
- `List<BookingVO> BookingService.listMyBookings(Long userId)`
- `List<BookingVO> BookingService.listAllBookings()`
- Lock: `smartoffice:booking:room:{roomId}`
- Conflict query: existing `startTime < newEnd && endTime > newStart` with status BOOKED.

- [ ] **Step 1: Add pure boundary tests: `10:00-11:00` and `11:00-12:00` do not conflict; partial/full overlaps conflict; `start >= end` is invalid.**
- [ ] **Step 2: Add Mockito service tests for Bloom negative, lock acquisition failure, missing room, maintenance room, conflict, successful insert, and `finally` unlock.**
- [ ] **Step 3: Run booking tests and verify failure.**
- [ ] **Step 4: Implement booking validation, `tryLock(waitTime, TimeUnit)` using Redisson Watchdog, then call a separate transactional `BookingWriteService` that rechecks the database and inserts before returning; only after that return may the outer service unlock. Add list endpoints.**
- [ ] **Step 5: Run booking tests and commit `feat: protect booking with redisson lock`.**

### Task 6: AOP operation logging

**Files:** Create `OperationLog` annotation and `OperationLogAspect`; annotate login-independent write endpoints such as booking and room mutations.

**Interfaces:**
- `@OperationLog("创建预约")`
- Around advice always rethrows original failures and logs elapsed milliseconds.

- [ ] **Step 1: Add an aspect test proving `proceed()` is called once and thrown exceptions are not swallowed.**
- [ ] **Step 2: Verify the test fails.**
- [ ] **Step 3: Implement annotation/aspect and annotate write operations.**
- [ ] **Step 4: Run aspect tests.**
- [ ] **Step 5: Commit `feat: add operation logging aspect`.**

### Task 7: Redis serialization, Knife4j, and configuration hardening

**Files:** Create/update Redis config and OpenAPI config; finalize `application.yml` environment substitutions.

**Interfaces:**
- Redis values support Java Time via Jackson JSR-310.
- Knife4j/OpenAPI endpoints are public while business endpoints remain protected.

- [ ] **Step 1: Add context/config tests for serialization and public docs paths where practical.**
- [ ] **Step 2: Verify tests fail before configuration.**
- [ ] **Step 3: Add RedisTemplate JSR-310 serializer, Knife4j metadata, CORS origins, and environment-backed DB/Redis/JWT settings.**
- [ ] **Step 4: Run config/security tests.**
- [ ] **Step 5: Commit `chore: harden redis and api documentation config`.**

### Task 8: Local/deployment assets and recovered frontend integration

**Files:** Create Docker assets, `.gitignore`, README; modify development env and only adjust frontend code where a backend contract mismatch is discovered.

**Interfaces:**
- Local frontend API base URL is `http://localhost:8081`.
- `docker-compose.dev.yml` starts MySQL 8 and Redis 7.
- Host-network script runs backend with `--env-file deploy/.env`.

- [ ] **Step 1: Add deployment files and local environment defaults without committing secrets.**
- [ ] **Step 2: Build the recovered Vue frontend with `npm run build`; fix only compile/API-contract issues.**
- [ ] **Step 3: Validate SQL syntax/config files statically and, when Maven is available, run `mvn test` and `mvn package`.**
- [ ] **Step 4: Write README covering startup, demo credentials, curl flow, Docker deployment, design trade-offs, and direct mapping from resume bullets to source files.**
- [ ] **Step 5: Commit `docs: add SmartOffice runbook and deployment`.**

### Task 9: End-to-end verification and clean deliverable

**Files:** No new feature files unless verification discovers a defect.

**Interfaces:** Complete repository artifact.

- [ ] **Step 1: Run full backend test/package suite if Maven is available; otherwise record the environment limitation and perform Java/source/config static verification without claiming backend tests passed.**
- [ ] **Step 2: Run `npm run build` and confirm successful frontend output.**
- [ ] **Step 3: Search for secrets, old cloud IPs, stale dead APIs, TODO/TBD placeholders, and accidental `node_modules`/build artifacts.**
- [ ] **Step 4: Review git diff/log and confirm each resume claim has concrete source implementation.**
- [ ] **Step 5: Package the repository as `smartoffice-rebuilt.zip` and provide it to the user with a concise verification report.**
