# Query Rewrite Hook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one fail-open model-backed query rewrite hook before external web search and internal hybrid knowledge retrieval.

**Architecture:** `QueryRewriteHook` is a retrieval-specific extension point shared by `SearchServiceImpl` and `RagServiceImpl`. `ModelQueryRewriteHook` uses `ChatClientRegistry` and JSON parsing, while both retrieval services remain unaware of prompt and model details. Failures always preserve the original query.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring AI `ChatClient`, Jackson, JUnit 5, Mockito, AssertJ.

---

## File Structure

- Create `src/main/java/com/kama/jchatmind/config/QueryRewriteProperties.java`: binds `jchatmind.query-rewrite`.
- Create `src/main/java/com/kama/jchatmind/retrieval/hook/RetrievalType.java`: distinguishes external and knowledge-base retrieval.
- Create `src/main/java/com/kama/jchatmind/retrieval/hook/QueryRewriteHook.java`: shared extension interface.
- Create `src/main/java/com/kama/jchatmind/retrieval/hook/ModelQueryRewriteHook.java`: prompt, model call, JSON parsing, and fail-open behavior.
- Modify `src/main/java/com/kama/jchatmind/search/impl/SearchServiceImpl.java`: rewrite before cache/provider lookup.
- Modify `src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java`: rewrite once before vector and BM25 recall.
- Modify `src/main/resources/application.yaml`: add enabled/model defaults.
- Create `src/test/java/com/kama/jchatmind/retrieval/hook/ModelQueryRewriteHookTest.java`.
- Create `src/test/java/com/kama/jchatmind/search/SearchServiceImplTest.java`.
- Create `src/test/java/com/kama/jchatmind/service/RagServiceQueryRewriteTest.java`.

### Task 1: Define and test model-backed Hook behavior

**Files:**
- Create: `src/test/java/com/kama/jchatmind/retrieval/hook/ModelQueryRewriteHookTest.java`
- Create: `src/main/java/com/kama/jchatmind/config/QueryRewriteProperties.java`
- Create: `src/main/java/com/kama/jchatmind/retrieval/hook/RetrievalType.java`
- Create: `src/main/java/com/kama/jchatmind/retrieval/hook/QueryRewriteHook.java`
- Create: `src/main/java/com/kama/jchatmind/retrieval/hook/ModelQueryRewriteHook.java`

- [ ] **Step 1: Write failing tests**

Test these behaviors:

```java
assertThat(hook.rewrite(RetrievalType.KNOWLEDGE_BASE, "请帮我查 JChatMind Hook"))
        .isEqualTo("JChatMind Hook 机制");

properties.setEnabled(false);
assertThat(hook.rewrite(RetrievalType.EXTERNAL_SEARCH, "原查询"))
        .isEqualTo("原查询");

when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
        .thenReturn("not-json");
assertThat(hook.rewrite(RetrievalType.EXTERNAL_SEARCH, "原查询"))
        .isEqualTo("原查询");
```

Also cover missing model, blank input, blank rewritten query, and model exceptions.

- [ ] **Step 2: Run the Hook test and verify RED**

Run:

```powershell
.\mvnw.cmd test "-Dtest=ModelQueryRewriteHookTest" "-Dmaven.repo.local=.m2/repository"
```

Expected: compilation failure because query rewrite types do not exist.

- [ ] **Step 3: Implement the minimal Hook**

Use these public contracts:

```java
public enum RetrievalType {
    EXTERNAL_SEARCH,
    KNOWLEDGE_BASE
}
```

```java
public interface QueryRewriteHook {
    String rewrite(RetrievalType retrievalType, String query);
}
```

`ModelQueryRewriteHook.rewrite()` must:

1. Return the input when disabled or blank.
2. Resolve `properties.getModel()` through `ChatClientRegistry`.
3. Call the model with the approved system/user prompt.
4. Parse `{"rewrittenQuery":"..."}` with Jackson.
5. Return the original query for every failure path.

- [ ] **Step 4: Run the Hook test and verify GREEN**

Run:

```powershell
.\mvnw.cmd test "-Dtest=ModelQueryRewriteHookTest" "-Dmaven.repo.local=.m2/repository"
```

Expected: all `ModelQueryRewriteHookTest` tests pass.

### Task 2: Integrate external search

**Files:**
- Create: `src/test/java/com/kama/jchatmind/search/SearchServiceImplTest.java`
- Modify: `src/main/java/com/kama/jchatmind/search/impl/SearchServiceImpl.java`

- [ ] **Step 1: Write the failing integration test**

Construct `SearchServiceImpl` with a capturing `SearchProvider` and a Hook returning `"rewritten query"`. Assert that:

```java
service.search(new SearchRequest("original query", 5, "month", List.of("example.com")));

assertThat(captured.get().query()).isEqualTo("rewritten query");
assertThat(captured.get().count()).isEqualTo(5);
assertThat(captured.get().timeRange()).isEqualTo("month");
assertThat(captured.get().domainFilter()).containsExactly("example.com");
```

- [ ] **Step 2: Run the external-search test and verify RED**

Run:

```powershell
.\mvnw.cmd test "-Dtest=SearchServiceImplTest" "-Dmaven.repo.local=.m2/repository"
```

Expected: constructor or assertion failure because `SearchServiceImpl` does not invoke the Hook.

- [ ] **Step 3: Implement external-search integration**

Inject `QueryRewriteHook`. At the beginning of `search()`:

```java
String rewrittenQuery = queryRewriteHook.rewrite(RetrievalType.EXTERNAL_SEARCH, request.query());
SearchRequest effectiveRequest = new SearchRequest(
        rewrittenQuery,
        request.count(),
        request.timeRange(),
        request.domainFilter()
);
```

Use `effectiveRequest` for availability errors, cache keys, provider calls, and error results.

- [ ] **Step 4: Run the external-search test and verify GREEN**

Run:

```powershell
.\mvnw.cmd test "-Dtest=SearchServiceImplTest" "-Dmaven.repo.local=.m2/repository"
```

Expected: test passes and provider receives the rewritten query.

### Task 3: Integrate internal hybrid retrieval

**Files:**
- Create: `src/test/java/com/kama/jchatmind/service/RagServiceQueryRewriteTest.java`
- Modify: `src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java`

- [ ] **Step 1: Write the failing integration test**

Mock `QueryRewriteHook` to return `"rewritten knowledge query"`, let vector recall degrade to empty, return one BM25 hit, and assert:

```java
verify(queryRewriteHook).rewrite(RetrievalType.KNOWLEDGE_BASE, "original query");
verify(bm25IndexManager).search("kb1", "rewritten knowledge query", properties.getBm25TopK());
assertThat(result.getQuery()).isEqualTo("rewritten knowledge query");
```

- [ ] **Step 2: Run the RAG test and verify RED**

Run:

```powershell
.\mvnw.cmd test "-Dtest=RagServiceQueryRewriteTest" "-Dmaven.repo.local=.m2/repository"
```

Expected: constructor or verification failure because `RagServiceImpl` does not invoke the Hook.

- [ ] **Step 3: Implement internal-retrieval integration**

At the start of `retrieve()`:

```java
String effectiveQuery = queryRewriteHook.rewrite(RetrievalType.KNOWLEDGE_BASE, query);
```

Use `effectiveQuery` in:

- vector recall;
- BM25 recall;
- vector-only fallback;
- `StructuredRetrievalResult.query`.

Call the Hook exactly once per `retrieve()` invocation.

- [ ] **Step 4: Run the RAG test and verify GREEN**

Run:

```powershell
.\mvnw.cmd test "-Dtest=RagServiceQueryRewriteTest" "-Dmaven.repo.local=.m2/repository"
```

Expected: test passes; both retrieval channels use the same rewritten query.

### Task 4: Add configuration and verify the feature

**Files:**
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Add configuration**

Under `jchatmind`:

```yaml
query-rewrite:
  enabled: true
  model: deepseek
```

- [ ] **Step 2: Run focused tests**

Run:

```powershell
.\mvnw.cmd test "-Dtest=ModelQueryRewriteHookTest,SearchServiceImplTest,RagServiceQueryRewriteTest,TavilySearchProviderTest,BM25IndexManagerTest" "-Dmaven.repo.local=.m2/repository"
```

Expected: all focused tests pass.

- [ ] **Step 3: Run the full test suite**

Run:

```powershell
.\mvnw.cmd test "-Dmaven.repo.local=.m2/repository"
```

Expected: Maven exits with code 0 and reports zero failures/errors.

- [ ] **Step 4: Check the final diff**

Run:

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors; `.understand-anything/` remains untracked and untouched.
