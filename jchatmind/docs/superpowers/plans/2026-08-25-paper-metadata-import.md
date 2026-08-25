# 论文元数据导入（第一步）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 `paper` 表并实现 WoS/CNKI 论文元数据的幂等批量导入与分页查询接口，为低轨卫星星座竞争力分析智能体提供论文证据层的数据基础。

**Architecture:** 新增 `paper` 单表（元数据 + 筛选状态合一），通过启动时幂等 DDL 初始化器建表（项目无 flyway/liquibase）；两个纯函数解析器（WoS CSV 制式 / CNKI RefWorks 制式）产出 `Paper` 实体，导入服务按 `doc_id` 先查后写实现幂等 upsert 并返回统计；Controller 暴露 SPEC 定义的三类接口。元数据导入为**同步接口**（万条级秒级完成，SPEC 中的"后台任务 + SSE"要求由后续论文全文导入计划满足）。

**Tech Stack:** Spring Boot 3.5.8 + MyBatis 3.0.3（XML mapper）+ PostgreSQL（uuid/jsonb）+ Apache Commons CSV 1.11.0（新增）+ Lombok + JUnit 5。

**Spec:** `SPEC.md`（本计划实施其"实施顺序建议 ①"，接口对应 SPEC 第 2 节导入接口表中 metadata / 查询 / stats 三行）

## Global Constraints

- 幂等导入：按 `doc_id` upsert，重复导入不产生重复数据，第二次导入 `inserted=0`（SPEC User Story 8、Testing Decisions）。
- CSV 解析需处理 BOM、引号内逗号、多值字段（作者/机构分号分隔）（SPEC Implementation Decisions 第 2 节）。
- 导入响应含总数/成功/失败/错误明细（最多 20 条），入库统计接口可按来源库与筛选状态分组核对（SPEC User Story 9）。
- 实体/Mapper/Controller/Service 严格沿用项目现有模式：实体 `@Data @Builder`；Mapper `@Mapper` 接口 + `resources/mapper/*.xml`（uuid 用 `CAST(#{x} AS uuid)`，jsonb 用 `CAST(#{x} AS jsonb)`）；Controller `@RestController @RequestMapping("/api") @AllArgsConstructor` 返回 `ApiResponse<T>`；Service 为 Facade 接口 + impl。
- 集成测试沿用 `DocumentBatchUploadTest` 模式：`@SpringBootTest` 直连本地 PostgreSQL（localhost:5432/jchatmind），前置要求写在类 javadoc；测试数据用 `WOS:TEST-` / `CNKI:TEST-` 前缀并在 `@AfterEach` 清理。本步所有测试不依赖 Ollama 与 LLM。
- 中文参数校验错误信息与中文代码注释，风格与现有代码一致。
- 不改动现有任何表与接口。

---

### Task 1: paper 表 DDL 与幂等建表初始化器

**Files:**
- Modify: `pom.xml`（dependencies 段末尾加 commons-csv）
- Create: `src/main/resources/db/paper-schema.sql`
- Create: `src/main/java/com/kama/jchatmind/config/PaperSchemaInitializer.java`
- Test: `src/test/java/com/kama/jchatmind/config/PaperSchemaInitializerTest.java`

**Interfaces:**
- Consumes: 无（首任务）
- Produces: 数据库中存在 `paper` 表与三个索引（后续所有任务依赖）；classpath 资源 `db/paper-schema.sql`

- [ ] **Step 1: 在 pom.xml 添加 commons-csv 依赖**

在 `pom.xml` 的 `<!-- 邮件发送 -->` 依赖块之前插入：

```xml
        <!-- CSV 解析（WoS 论文元数据导入） -->
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-csv</artifactId>
            <version>1.11.0</version>
        </dependency>
```

- [ ] **Step 2: 写失败的集成测试**

创建 `src/test/java/com/kama/jchatmind/config/PaperSchemaInitializerTest.java`：

```java
package com.kama.jchatmind.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * paper 表幂等建表初始化器测试
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
public class PaperSchemaInitializerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void paperTableShouldExistAfterContextStartup() {
        // to_regclass 返回表在 public 模式下的注册名；不存在时为 null
        String regClass = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.paper')::text", String.class);
        assertNotNull(regClass, "paper 表应在应用启动后存在");
        assertEquals("paper", regClass);
    }

    @Test
    public void docIdUniqueConstraintShouldExist() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conrelid = 'public.paper'::regclass AND conname = 'uk_paper_doc_id'",
                Integer.class);
        assertNotNull(count);
        assertEquals(1, "doc_id 唯一约束应存在（幂等 upsert 的前提）", count);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn test -Dtest=PaperSchemaInitializerTest -q`
Expected: FAIL —— `paperTableShouldExistAfterContextStartup` 断言失败（regClass 为 null，表尚不存在）

- [ ] **Step 4: 创建 DDL 脚本**

创建 `src/main/resources/db/paper-schema.sql`（幂等，可重复执行）：

```sql
-- 论文元数据表（含筛选结论，SPEC 数据模型第 1 节）
-- doc_id: WoS 为 UT（WOS:xxxx），CNKI 为构造 ID（CNKI:md5hex）
-- screening_status: pending（未筛选）/ included（有效）/ excluded（排除）
CREATE TABLE IF NOT EXISTS paper
(
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id                 VARCHAR(128) NOT NULL,
    source_db              VARCHAR(16)  NOT NULL,
    title                  TEXT         NOT NULL,
    abstract_text          TEXT,
    authors                JSONB,
    affiliations           JSONB,
    first_author           VARCHAR(512),
    first_author_affiliation TEXT,
    first_author_country   VARCHAR(64),
    country_evidence       TEXT,
    country_confidence     VARCHAR(32),
    publish_year           INT,
    journal                VARCHAR(512),
    doc_type               VARCHAR(64),
    cited_count            INT,
    doi                    VARCHAR(256),
    keywords               TEXT,
    screening_status       VARCHAR(32) NOT NULL DEFAULT 'pending',
    exclude_reason         TEXT,
    file_name              VARCHAR(512),
    created_at             TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_paper_doc_id UNIQUE (doc_id)
);

CREATE INDEX IF NOT EXISTS idx_paper_year ON paper (publish_year);
CREATE INDEX IF NOT EXISTS idx_paper_country ON paper (first_author_country);
CREATE INDEX IF NOT EXISTS idx_paper_screening ON paper (screening_status);
```

- [ ] **Step 5: 创建初始化器**

创建 `src/main/java/com/kama/jchatmind/config/PaperSchemaInitializer.java`：

```java
package com.kama.jchatmind.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/**
 * paper 表幂等建表初始化器
 * 应用启动时执行 classpath:db/paper-schema.sql（全部为 CREATE ... IF NOT EXISTS，可安全重复执行）
 */
@Slf4j
@Component
public class PaperSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    public PaperSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initialize() {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/paper-schema.sql"));
            log.info("paper 表结构初始化完成（幂等）");
        } catch (Exception e) {
            throw new IllegalStateException("paper 表结构初始化失败", e);
        }
    }
}
```

再创建配置注册（同包）`src/main/java/com/kama/jchatmind/config/PaperSchemaConfiguration.java`：

```java
package com.kama.jchatmind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 论文相关 Bean 配置
 */
@Configuration
public class PaperSchemaConfiguration {

    /**
     * 建表逻辑作为 Bean 的初始化回调执行，保证任何依赖 paper 表的 Bean 使用前表已存在
     */
    @Bean(initMethod = "initialize")
    public PaperSchemaInitializer paperSchemaInitializer(PaperSchemaInitializer initializer) {
        return initializer;
    }
}
```

等等——`PaperSchemaInitializer` 已标注 `@Component`，再加 `@Bean` 会重复注册。二选一：删掉 `@Component` 注解、只保留 `@Bean(initMethod = "initialize")`。**修正：`PaperSchemaInitializer` 类上不加 `@Component`**，仅通过 `PaperSchemaConfiguration` 的 `@Bean(initMethod = "initialize")` 注册。最终 `PaperSchemaInitializer.java` 去掉 `@Component` 注解，保留构造器注入与 `initialize()` 方法。

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn test -Dtest=PaperSchemaInitializerTest -q`
Expected: PASS（2 个测试）

- [ ] **Step 7: 提交**

```bash
git add pom.xml src/main/resources/db/paper-schema.sql src/main/java/com/kama/jchatmind/config/PaperSchemaInitializer.java src/main/java/com/kama/jchatmind/config/PaperSchemaConfiguration.java src/test/java/com/kama/jchatmind/config/PaperSchemaInitializerTest.java
git commit -m "feat: paper表DDL与幂等建表初始化器"
```

---

### Task 2: Paper 实体与 Mapper（upsert + 按 docId 查询）

**Files:**
- Create: `src/main/java/com/kama/jchatmind/model/entity/Paper.java`
- Create: `src/main/java/com/kama/jchatmind/mapper/PaperMapper.java`
- Create: `src/main/resources/mapper/PaperMapper.xml`
- Test: `src/test/java/com/kama/jchatmind/mapper/PaperMapperUpsertTest.java`

**Interfaces:**
- Consumes: Task 1 的 `paper` 表
- Produces:
  - `Paper` 实体（字段见下方代码，后续所有任务使用）
  - `PaperMapper#upsert(Paper paper): int` —— 按 `doc_id` 冲突时更新，返回影响行数
  - `PaperMapper#selectByDocId(String docId): Paper`
  - `PaperMapper#deleteByDocIdPrefix(String prefix): int`（测试清理用）

- [ ] **Step 1: 写失败的集成测试**

创建 `src/test/java/com/kama/jchatmind/mapper/PaperMapperUpsertTest.java`：

```java
package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.Paper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Paper upsert 幂等测试
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
public class PaperMapperUpsertTest {

    private static final String TEST_DOC_ID = "WOS:TEST-0001";

    @Autowired
    private PaperMapper paperMapper;

    @AfterEach
    public void cleanup() {
        paperMapper.deleteByDocIdPrefix("WOS:TEST-");
    }

    private Paper buildPaper() {
        return Paper.builder()
                .docId(TEST_DOC_ID)
                .sourceDb("WOS")
                .title("Test Paper Title")
                .abstractText("Test abstract")
                .authors("[\"Author, A\",\"Author, B\"]")
                .affiliations("[\"Inst A, Beijing, China\"]")
                .firstAuthor("Author, A")
                .firstAuthorAffiliation("Inst A, Beijing, China")
                .publishYear(2024)
                .journal("Test Journal")
                .docType("Article")
                .citedCount(5)
                .doi("10.1000/test")
                .screeningStatus("pending")
                .build();
    }

    @Test
    public void upsertShouldInsertNewPaper() {
        int rows = paperMapper.upsert(buildPaper());
        assertEquals(1, rows);

        Paper saved = paperMapper.selectByDocId(TEST_DOC_ID);
        assertNotNull(saved);
        assertEquals("Test Paper Title", saved.getTitle());
        assertEquals("WOS", saved.getSourceDb());
        assertEquals(2024, saved.getPublishYear());
        assertEquals("[\"Author, A\",\"Author, B\"]", saved.getAuthors());
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    public void upsertShouldUpdateExistingPaperWithoutDuplicate() {
        paperMapper.upsert(buildPaper());

        // 同 doc_id 变更字段后再次 upsert
        Paper updated = buildPaper();
        updated.setTitle("Updated Title");
        updated.setCitedCount(10);
        updated.setFirstAuthorCountry("CN");
        int rows = paperMapper.upsert(updated);
        assertEquals(1, rows);

        Paper saved = paperMapper.selectByDocId(TEST_DOC_ID);
        assertEquals("Updated Title", saved.getTitle());
        assertEquals(10, saved.getCitedCount());
        assertEquals("CN", saved.getFirstAuthorCountry());

        // 幂等：表中仍只有一条
        Integer count = countByDocId();
        assertEquals(1, count);
    }

    @Test
    public void selectByDocIdShouldReturnNullWhenAbsent() {
        assertNull(paperMapper.selectByDocId("WOS:TEST-NOT-EXIST"));
    }

    @SuppressWarnings("SqlSourceToSink")
    private Integer countByDocId() {
        return paperMapper.countByDocId(TEST_DOC_ID);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=PaperMapperUpsertTest -q`
Expected: 编译失败 —— `Paper` 与 `PaperMapper` 不存在

- [ ] **Step 3: 创建 Paper 实体**

创建 `src/main/java/com/kama/jchatmind/model/entity/Paper.java`：

```java
package com.kama.jchatmind.model.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * 论文元数据实体（对应表 paper）
 * doc_id 规则：WoS 为 UT 值（如 WOS:000388603100003）；CNKI 为构造 ID（CNKI: + md5(title|journal|year) 的 hex）
 * authors / affiliations 存 JSON 数组字符串，Java 侧不拆解
 */
@Data
@Builder
public class Paper {
    private String id;

    private String docId;

    /** 来源库：WOS / CNKI */
    private String sourceDb;

    private String title;

    private String abstractText;

    /** JSON 数组字符串，如 ["Author, A","Author, B"] */
    private String authors;

    /** JSON 数组字符串，机构地址列表 */
    private String affiliations;

    private String firstAuthor;

    private String firstAuthorAffiliation;

    /** 第一作者国别（CN/US/...），由筛选结论导入填充，元数据导入时为空 */
    private String firstAuthorCountry;

    private String countryEvidence;

    private String countryConfidence;

    private Integer publishYear;

    private String journal;

    private String docType;

    private Integer citedCount;

    private String doi;

    /** 关键词，保留源分隔符原样字符串 */
    private String keywords;

    /** pending / included / excluded */
    private String screeningStatus;

    private String excludeReason;

    private String fileName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
```

- [ ] **Step 4: 创建 Mapper 接口**

创建 `src/main/java/com/kama/jchatmind/mapper/PaperMapper.java`：

```java
package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.Paper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 针对表【paper】的数据库操作 Mapper
 */
@Mapper
public interface PaperMapper {

    /** 按 doc_id 插入或更新（ON CONFLICT DO UPDATE），返回影响行数 */
    int upsert(Paper paper);

    Paper selectByDocId(@Param("docId") String docId);

    int countByDocId(@Param("docId") String docId);

    /** 删除 doc_id 以 prefix 开头的记录（测试清理用） */
    int deleteByDocIdPrefix(@Param("prefix") String prefix);
}
```

- [ ] **Step 5: 创建 Mapper XML**

创建 `src/main/resources/mapper/PaperMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper
        PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.kama.jchatmind.mapper.PaperMapper">

    <resultMap id="BaseResultMap" type="com.kama.jchatmind.model.entity.Paper">
        <id property="id" column="id" jdbcType="VARCHAR"/>
        <result property="docId" column="doc_id" jdbcType="VARCHAR"/>
        <result property="sourceDb" column="source_db" jdbcType="VARCHAR"/>
        <result property="title" column="title" jdbcType="VARCHAR"/>
        <result property="abstractText" column="abstract_text" jdbcType="VARCHAR"/>
        <result property="authors" column="authors" jdbcType="VARCHAR"/>
        <result property="affiliations" column="affiliations" jdbcType="VARCHAR"/>
        <result property="firstAuthor" column="first_author" jdbcType="VARCHAR"/>
        <result property="firstAuthorAffiliation" column="first_author_affiliation" jdbcType="VARCHAR"/>
        <result property="firstAuthorCountry" column="first_author_country" jdbcType="VARCHAR"/>
        <result property="countryEvidence" column="country_evidence" jdbcType="VARCHAR"/>
        <result property="countryConfidence" column="country_confidence" jdbcType="VARCHAR"/>
        <result property="publishYear" column="publish_year" jdbcType="INTEGER"/>
        <result property="journal" column="journal" jdbcType="VARCHAR"/>
        <result property="docType" column="doc_type" jdbcType="VARCHAR"/>
        <result property="citedCount" column="cited_count" jdbcType="INTEGER"/>
        <result property="doi" column="doi" jdbcType="VARCHAR"/>
        <result property="keywords" column="keywords" jdbcType="VARCHAR"/>
        <result property="screeningStatus" column="screening_status" jdbcType="VARCHAR"/>
        <result property="excludeReason" column="exclude_reason" jdbcType="VARCHAR"/>
        <result property="fileName" column="file_name" jdbcType="VARCHAR"/>
        <result property="createdAt" column="created_at" jdbcType="TIMESTAMP"/>
        <result property="updatedAt" column="updated_at" jdbcType="TIMESTAMP"/>
    </resultMap>

    <sql id="Base_Column_List">
        id,doc_id,source_db,title,
        abstract_text,authors,affiliations,
        first_author,first_author_affiliation,first_author_country,
        country_evidence,country_confidence,publish_year,
        journal,doc_type,cited_count,
        doi,keywords,screening_status,
        exclude_reason,file_name,created_at,updated_at
    </sql>

    <insert id="upsert" parameterType="com.kama.jchatmind.model.entity.Paper"
            keyColumn="id" keyProperty="id" useGeneratedKeys="true">
        INSERT INTO paper
        (
            doc_id, source_db, title, abstract_text,
            authors, affiliations, first_author, first_author_affiliation,
            first_author_country, country_evidence, country_confidence,
            publish_year, journal, doc_type, cited_count,
            doi, keywords, screening_status,
            exclude_reason, file_name, created_at, updated_at
        )
        VALUES
        (
            #{docId}, #{sourceDb}, #{title}, #{abstractText},
            CAST(#{authors} AS jsonb), CAST(#{affiliations} AS jsonb),
            #{firstAuthor}, #{firstAuthorAffiliation},
            #{firstAuthorCountry}, #{countryEvidence}, #{countryConfidence},
            #{publishYear}, #{journal}, #{docType}, #{citedCount},
            #{doi}, #{keywords}, #{screeningStatus},
            #{excludeReason}, #{fileName}, now(), now()
        )
        ON CONFLICT (doc_id) DO UPDATE SET
            source_db = EXCLUDED.source_db,
            title = EXCLUDED.title,
            abstract_text = EXCLUDED.abstract_text,
            authors = EXCLUDED.authors,
            affiliations = EXCLUDED.affiliations,
            first_author = EXCLUDED.first_author,
            first_author_affiliation = EXCLUDED.first_author_affiliation,
            first_author_country = COALESCE(EXCLUDED.first_author_country, paper.first_author_country),
            country_evidence = COALESCE(EXCLUDED.country_evidence, paper.country_evidence),
            country_confidence = COALESCE(EXCLUDED.country_confidence, paper.country_confidence),
            publish_year = EXCLUDED.publish_year,
            journal = EXCLUDED.journal,
            doc_type = EXCLUDED.doc_type,
            cited_count = EXCLUDED.cited_count,
            doi = EXCLUDED.doi,
            keywords = EXCLUDED.keywords,
            screening_status = EXCLUDED.screening_status,
            exclude_reason = COALESCE(EXCLUDED.exclude_reason, paper.exclude_reason),
            file_name = COALESCE(EXCLUDED.file_name, paper.file_name),
            updated_at = now()
    </insert>

    <select id="selectByDocId" resultMap="BaseResultMap">
        SELECT id,
               doc_id, source_db, title,
               abstract_text, authors::text AS authors, affiliations::text AS affiliations,
               first_author, first_author_affiliation, first_author_country,
               country_evidence, country_confidence, publish_year,
               journal, doc_type, cited_count,
               doi, keywords, screening_status,
               exclude_reason, file_name, created_at, updated_at
        FROM paper
        WHERE doc_id = #{docId}
    </select>

    <select id="countByDocId" resultType="int">
        SELECT COUNT(*)
        FROM paper
        WHERE doc_id = #{docId}
    </select>

    <delete id="deleteByDocIdPrefix">
        DELETE
        FROM paper
        WHERE doc_id LIKE CONCAT(#{prefix}, '%')
    </delete>
</mapper>
```

注意 upsert 的 COALESCE 语义：国别、证据、排除原因、文件名这些**由筛选导入（第二步）填充的字段**，在元数据重复导入时不能被空值覆盖。

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn test -Dtest=PaperMapperUpsertTest -q`
Expected: PASS（3 个测试）

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/kama/jchatmind/model/entity/Paper.java src/main/java/com/kama/jchatmind/mapper/PaperMapper.java src/main/resources/mapper/PaperMapper.xml src/test/java/com/kama/jchatmind/mapper/PaperMapperUpsertTest.java
git commit -m "feat: Paper实体与Mapper upsert幂等写入"
```

---

### Task 3: JSON 工具与 WoS CSV 解析器

**Files:**
- Create: `src/main/java/com/kama/jchatmind/service/paper/PaperJson.java`
- Create: `src/main/java/com/kama/jchatmind/service/paper/WosCsvParser.java`
- Test: `src/test/java/com/kama/jchatmind/service/paper/WosCsvParserTest.java`

**Interfaces:**
- Consumes: Task 2 的 `Paper` 实体
- Produces:
  - `PaperJson#toJson(List<String> list): String` —— 转紧凑 JSON 数组字符串（元素为 null 时返回 null）
  - `WosCsvParser#parse(InputStream in): List<Paper>` —— throws IOException；UT 缺失的行抛出带行信息的 `PaperParseException`
  - `com.kama.jchatmind.service.paper.PaperParseException`（RuntimeException，字段 `lineNumber`）

- [ ] **Step 1: 写失败的单元测试（纯 JUnit，无 Spring）**

创建 `src/test/java/com/kama/jchatmind/service/paper/WosCsvParserTest.java`：

```java
package com.kama.jchatmind.service.paper;

import com.kama.jchatmind.model.entity.Paper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WoS CSV 解析器单元测试（纯函数，无 Spring/DB 依赖）
 */
public class WosCsvParserTest {

    private final WosCsvParser parser = new WosCsvParser();

    /**
     * 样例覆盖：BOM 表头、引号内逗号、分号多值（AU/C1）、空 TC、缺 DI
     */
    private static final String SAMPLE_CSV =
            "\uFEFFPT,AU,GP,TI,SO,SE,DT,PD,PY,AB,CT,CY,CL,SP,RI,ZB,ZS,ZR,Z8,TC,ZA,Z9,C1,SN,BN,DA,UT,BE,VL,AR,DI\n"
            + "C,\"Sahin, Selami; Ozbilgin, Tugba\",IEEE,\"On the Performance of Downlink, Optical Communication\","
            + "TEST JOURNAL,Test,Proceedings Paper,2016,2016,\"Abstract with, comma\",CT,CY,CL,SP,RI,0,0,0,0,,0,0,"
            + "\"TUBITAK, Kocaeli, Turkey; Univ, Ankara, Turkey\",1525-3511,,2016-01-01,WOS:TEST-0001,,,,\n"
            + "C,\"Doe, John\",,Single Author Paper,TEST JOURNAL 2,,Article,2020,2020,Abstract2,CT,CY,CL,SP,RI,0,0,0,0,3,0,0,"
            + "\"MIT, Cambridge, USA\",1111-2222,,2020-01-01,WOS:TEST-0002,,,10.1000/x\n";

    @Test
    public void shouldParseAllFieldsWithBomAndQuotedCommas() throws IOException {
        List<Paper> papers = parser.parse(new ByteArrayInputStream(SAMPLE_CSV.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, papers.size());

        Paper first = papers.get(0);
        assertEquals("WOS:TEST-0001", first.getDocId());
        assertEquals("WOS", first.getSourceDb());
        // 引号内的逗号不能破坏字段
        assertEquals("On the Performance of Downlink, Optical Communication", first.getTitle());
        // AU 按 "; " 分割为 JSON 数组，firstAuthor 取第一个
        assertEquals("[\"Sahin, Selami\",\"Ozbilgin, Tugba\"]", first.getAuthors());
        assertEquals("Sahin, Selami", first.getFirstAuthor());
        // C1 按 "; " 分割
        assertEquals("[\"TUBITAK, Kocaeli, Turkey\",\"Univ, Ankara, Turkey\"]", first.getAffiliations());
        assertEquals("TUBITAX".replace('X', 'B') + ", Kocaeli, Turkey", first.getFirstAuthorAffiliation());
        assertEquals(2016, first.getPublishYear());
        assertEquals("TEST JOURNAL", first.getJournal());
        assertEquals("Proceedings Paper", first.getDocType());
        // 空 TC 解析为 null
        assertNull(first.getCitedCount());
        assertNull(first.getDoi());
        // 元数据导入时国别未判定
        assertNull(first.getFirstAuthorCountry());
        assertEquals("pending", first.getScreeningStatus());

        Paper second = papers.get(1);
        assertEquals("WOS:TEST-0002", second.getDocId());
        assertEquals(3, second.getCitedCount());
        assertEquals("10.1000/x", second.getDoi());
        assertEquals("Doe, John", second.getFirstAuthor());
    }

    @Test
    public void shouldRejectRowMissingUt() {
        String csv = "PT,AU,TI,PY,UT\n" + "C,\"A, B\",Title,2020,\n";
        PaperParseException ex = assertThrows(PaperParseException.class,
                () -> parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))));
        assertTrue(ex.getMessage().contains("UT"));
        assertEquals(2, ex.getLineNumber());
    }

    @Test
    public void shouldTolerateUnparsableYear() throws IOException {
        String csv = "PT,AU,TI,PY,UT\n" + "C,\"A, B\",Title,not-a-year,WOS:TEST-0003\n";
        List<Paper> papers = parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, papers.size());
        assertNull(papers.get(0).getPublishYear());
    }

    @Test
    public void paperJsonShouldRoundTripLists() {
        assertNull(PaperJson.toJson(null));
        assertEquals("[\"a\",\"b\"]", PaperJson.toJson(List.of("a", "b")));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=WosCsvParserTest -q`
Expected: 编译失败 —— `WosCsvParser`、`PaperJson`、`PaperParseException` 不存在

- [ ] **Step 3: 实现 PaperJson、PaperParseException、WosCsvParser**

创建 `src/main/java/com/kama/jchatmind/service/paper/PaperParseException.java`：

```java
package com.kama.jchatmind.service.paper;

import lombok.Getter;

/**
 * 论文解析异常（携带 CSV 行号或记录序号）
 */
@Getter
public class PaperParseException extends RuntimeException {

    private final int lineNumber;

    public PaperParseException(String message, int lineNumber) {
        super(message + "（第 " + lineNumber + " 行）");
        this.lineNumber = lineNumber;
    }
}
```

创建 `src/main/java/com/kama/jchatmind/service/paper/PaperJson.java`：

```java
package com.kama.jchatmind.service.paper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 论文列表字段（作者/机构）的 JSON 序列化工具
 */
public final class PaperJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PaperJson() {
    }

    /**
     * 列表转紧凑 JSON 数组字符串；入参 null 时返回 null（保持数据库列可空）
     */
    public static String toJson(List<String> list) {
        if (list == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("作者/机构序列化失败", e);
        }
    }
}
```

创建 `src/main/java/com/kama/jchatmind/service/paper/WosCsvParser.java`：

```java
package com.kama.jchatmind.service.paper;

import com.kama.jchatmind.model.entity.Paper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * WoS 导出 CSV 解析器（表头制式：PT,AU,TI,SO,DT,PY,AB,TC,C1,UT,DI,...）
 * 处理要点：UTF-8 BOM、引号内逗号、分号分隔多值字段（AU/C1）
 */
@Component
public class WosCsvParser {

    public List<Paper> parse(InputStream in) throws IOException {
        List<Paper> papers = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            // 跳过 UTF-8 BOM，否则首列表头读作 "\uFEFFPT"
            reader.mark(1);
            int firstChar = reader.read();
            if (firstChar != '\uFEFF' && firstChar != -1) {
                reader.reset();
            }

            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .build();
            try (CSVParser csvParser = CSVParser.builder()
                    .setReader(reader)
                    .setFormat(format)
                    .get()) {
                for (CSVRecord record : csvParser) {
                    papers.add(toPaper(record));
                }
            }
        }
        return papers;
    }

    private Paper toPaper(CSVRecord record) {
        long recordNumber = record.getRecordNumber() + 1; // 含表头的物理行号近似

        String ut = get(record, "UT");
        if (ut == null || ut.isBlank()) {
            throw new PaperParseException("WoS 记录缺少 UT 唯一标识，无法导入", (int) recordNumber);
        }

        List<String> authors = splitBySemicolon(get(record, "AU"));
        List<String> affiliations = splitBySemicolon(get(record, "C1"));

        return Paper.builder()
                .docId(ut.trim())
                .sourceDb("WOS")
                .title(orNull(get(record, "TI")))
                .abstractText(orNull(get(record, "AB")))
                .authors(PaperJson.toJson(authiliatesSafe(authors)))
                .affiliations(PaperJson.toJson(affiliations))
                .firstAuthor(authors == null || authors.isEmpty() ? null : authors.get(0))
                .firstAuthorAffiliation(affiliations == null || affiliations.isEmpty() ? null : affiliations.get(0))
                .publishYear(parseInteger(get(record, "PY")))
                .journal(orNull(get(record, "SO")))
                .docType(orNull(get(record, "DT")))
                .citedCount(parseInteger(get(record, "TC")))
                .doi(orNull(get(record, "DI")))
                .screeningStatus("pending")
                .build();
    }

    private List<String> authiliatesSafe(List<String> authors) {
        return authors;
    }

    /** 按分号+可选空格分割多值字段，返回 null 保持列可空 */
    private List<String> splitBySemicolon(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(value.split("\\s*;\\s*"))
                .filter(s -> !s.isBlank())
                .toList();
    }

    private String get(CSVRecord record, String column) {
        return record.isSet(column) ? record.get(column) : null;
    }

    private String orNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Integer parseInteger(String value) {
        String v = orNull(value);
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null; // 年份/被引字段格式异常时容错为 null
        }
    }
}
```

实现后删除无意义的 `authiliatesSafe` 私有方法，`authors` 直接传 `PaperJson.toJson(authors)`（上方法体中已如此占位——实现时写干净版本）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=WosCsvParserTest -q`
Expected: PASS（4 个测试）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/kama/jchatmind/service/paper/PaperJson.java src/main/java/com/kama/jchatmind/service/paper/PaperParseException.java src/main/java/com/kama/jchatmind/service/paper/WosCsvParser.java src/test/java/com/kama/jchatmind/service/paper/WosCsvParserTest.java
git commit -m "feat: WoS CSV解析器（BOM/引号逗号/多值字段）"
```

---

### Task 4: CNKI RefWorks 制式解析器

**Files:**
- Create: `src/main/java/com/kama/jchatmind/service/paper/CnkiRefWorksParser.java`
- Test: `src/test/java/com/kama/jchatmind/service/paper/CnkiRefWorksParserTest.java`

**Interfaces:**
- Consumes: Task 3 的 `Paper`、`PaperJson`
- Produces: `CnkiRefWorksParser#parse(InputStream in): List<Paper>` —— throws IOException；CNKI 无 UT，docId = `"CNKI:" + md5Hex(title + "|" + journal + "|" + publishYear)`

- [ ] **Step 1: 写失败的单元测试**

创建 `src/test/java/com/kama/jchatmind/service/paper/CnkiRefWorksParserTest.java`（样例取自真实 `知网论文数据.txt` 前两条记录的精简版）：

```java
package com.kama.jchatmind.service.paper;

import com.kama.jchatmind.model.entity.Paper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CNKI RefWorks 制式解析器单元测试
 */
public class CnkiRefWorksParserTest {

    private final CnkiRefWorksParser parser = new CnkiRefWorksParser();

    private static final String SAMPLE = """
            RT Journal Article
            SR 1
            A1 赵晶;王志浩;虞志刚
            AD 应急管理大学应急技术与管理学院;中国电子科技集团有限公司电子科学研究院;
            T1 面向空天地一体化网络的数字孪生系统架构
            JF 电讯技术
            YR 2026
            K1 空天地一体化网络;数字孪生
            AB 摘要文本。
            DO 10.20079/j.issn.1001-893x.260117001

            RT Journal Article
            SR 1
            A1 马越辰;黄美丽
            AD 北京空间飞行器总体设计部;
            T1 航天器轨道及星座设计与优化软件开发应用
            JF 航天器工程
            YR 2026
            AB 摘要二。

            """;

    @Test
    public void shouldParseRefWorksRecords() throws IOException {
        List<Paper> papers = parser.parse(new ByteArrayInputStream(SAMPLE.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, papers.size());

        Paper first = papers.get(0);
        assertEquals("CNKI", first.getSourceDb());
        assertEquals("面向空天地一体化网络的数字孪生系统架构", first.getTitle());
        assertEquals("[\"赵晶\",\"王志浩\",\"虞志刚\"]", first.getAuthors());
        assertEquals("赵晶", first.getFirstAuthor());
        // AD 尾部分号不应产生空机构
        assertEquals("[\"应急管理大学应急技术与管理学院\",\"中国电子科技集团有限公司电子科学研究院\"]",
                first.getAffiliations());
        assertEquals("应急管理大学应急技术与管理学院", first.getFirstAuthorAffiliation());
        assertEquals(2026, first.getPublishYear());
        assertEquals("电讯技术", first.getJournal());
        assertEquals("10.20079/j.issn.1001-893x.260117001", first.getDoi());
        assertEquals("空天地一体化网络;数字孪生", first.getKeywords());
        assertEquals("Journal Article", first.getDocType());
        assertEquals("pending", first.getScreeningStatus());
        // CNKI 无被引数据
        assertNull(first.getCitedCount());

        Paper second = papers.get(1);
        assertEquals("航天器轨道及星座设计与优化软件开发应用", second.getTitle());
        assertNull(second.getDoi());
        assertNull(second.getKeywords());
    }

    @Test
    public void docIdShouldBeDeterministicAndDerivedFromTitleJournalYear() throws IOException {
        List<Paper> papers = parser.parse(new ByteArrayInputStream(SAMPLE.getBytes(StandardCharsets.UTF_8)));

        String docId = papers.get(0).getDocId();
        assertTrue(docId.startsWith("CNKI:"), "docId 应以 CNKI: 前缀开头");
        assertEquals("CNKI:" + CnkiRefWorksParser.md5Hex("面向空天地一体化网络的数字孪生系统架构|电讯技术|2026"), docId);

        // 重复解析结果稳定（幂等导入的前提）
        List<Paper> again = parser.parse(new ByteArrayInputStream(SAMPLE.getBytes(StandardCharsets.UTF_8)));
        assertEquals(docId, again.get(0).getDocId());
    }

    @Test
    public void recordWithoutTitleShouldBeSkipped() throws IOException {
        String sample = "RT Journal Article\nSR 1\nJF 某期刊\nYR 2026\n\n";
        List<Paper> papers = parser.parse(new ByteArrayInputStream(sample.getBytes(StandardCharsets.UTF_8)));
        assertTrue(papers.isEmpty(), "缺少 T1 标题的记录无法构造 docId，应跳过");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=CnkiRefWorksParserTest -q`
Expected: 编译失败 —— `CnkiRefWorksParser` 不存在

- [ ] **Step 3: 实现 CnkiRefWorksParser**

创建 `src/main/java/com/kama/jchatmind/service/paper/CnkiRefWorksParser.java`：

```java
package com.kama.jchatmind.service.paper;

import com.kama.jchatmind.model.entity.Paper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CNKI RefWorks 制式解析器
 * 制式：每条记录由 "XX 值" 行构成（RT/A1/AD/T1/JF/YR/AB/K1/DO 等），记录间以空行分隔
 * docId 构造规则：CNKI: + md5Hex(title|journal|year)，CNKI 导出无 UT 唯一标识
 */
@Component
public class CnkiRefWorksParser {

    public List<Paper> parse(InputStream in) throws IOException {
        List<Paper> papers = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    addIfValid(current, papers);
                    current = new LinkedHashMap<>();
                } else if (line.length() >= 2) {
                    // 行格式：两位代码 + 空格 + 值（如 "T1 标题"）
                    String code = line.substring(0, 2);
                    String value = line.length() > 3 ? line.substring(3) : "";
                    current.put(code, value);
                }
            }
        }
        // 文件末尾无空行时的最后一条记录
        addIfValid(current, papers);
        return papers;
    }

    private void addIfValid(Map<String, String> fields, List<Paper> papers) {
        if (fields.isEmpty()) {
            return;
        }
        String title = orNull(fields.get("T1"));
        if (title == null) {
            return; // 无标题无法构造稳定 docId，跳过
        }
        String journal = orNull(fields.get("JF"));
        String yearStr = orNull(fields.get("YR"));

        List<String> authors = splitBySemicolon(fields.get("A1"));
        List<String> affiliations = splitBySemicolon(fields.get("AD"));

        papers.add(Paper.builder()
                .docId("CNKI:" + md5Hex(title + "|" + journal + "|" + yearStr))
                .sourceDb("CNKI")
                .title(title)
                .abstractText(orNull(fields.get("AB")))
                .authors(PaperJson.toJson(authors))
                .affiliations(PaperJson.toJson(affiliations))
                .firstAuthor(authors == null || authors.isEmpty() ? null : authors.get(0))
                .firstAuthorAffiliation(affiliations == null || affiliations.isEmpty() ? null : affiliations.get(0))
                .publishYear(parseInteger(yearStr))
                .journal(journal)
                .docType(orNull(fields.get("RT")))
                .doi(orNull(fields.get("DO")))
                .keywords(orNull(fields.get("K1")))
                .screeningStatus("pending")
                .build());
    }

    /** 中文分号分隔多值字段（A1/AD 用 ";"），过滤空段（含尾部分号） */
    private List<String> splitBySemicolon(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String s : value.split(";")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts.isEmpty() ? null : parts;
    }

    private String orNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Integer parseInteger(String value) {
        String v = orNull(value);
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 供测试断言 docId 构造规则 */
    public static String md5Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
```

（实现时去掉重复的 `import java.nio.charset.StandardCharsets;` 行——只保留一处。）

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=CnkiRefWorksParserTest -q`
Expected: PASS（3 个测试）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/kama/jchatmind/service/paper/CnkiRefWorksParser.java src/test/java/com/kama/jchatmind/service/paper/CnkiRefWorksParserTest.java
git commit -m "feat: CNKI RefWorks制式解析器与稳定docId构造"
```

---

### Task 5: 分页条件查询（动态 SQL）

**Files:**
- Create: `src/main/java/com/kama/jchatmind/model/request/PaperQueryRequest.java`
- Modify: `src/main/java/com/kama/jchatmind/mapper/PaperMapper.java`（追加 2 个方法）
- Modify: `src/main/resources/mapper/PaperMapper.xml`（追加 selectByCondition / countByCondition）
- Test: `src/test/java/com/kama/jchatmind/mapper/PaperMapperQueryTest.java`

**Interfaces:**
- Consumes: Task 2 的 `PaperMapper`、`Paper`
- Produces:
  - `PaperQueryRequest`：字段 `sourceDb, country, yearFrom, yearTo, screeningStatus, keyword, page(默认1), pageSize(默认20)` + `getOffset()` 方法
  - `PaperMapper#selectByCondition(PaperQueryRequest query): List<Paper>`
  - `PaperMapper#countByCondition(PaperQueryRequest query): long`

- [ ] **Step 1: 写失败的集成测试**

创建 `src/test/java/com/kama/jchatmind/mapper/PaperMapperQueryTest.java`：

```java
package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.Paper;
import com.kama.jchatmind.model.request.PaperQueryRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Paper 分页条件查询集成测试
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
public class PaperMapperQueryTest {

    @Autowired
    private PaperMapper paperMapper;

    @BeforeEach
    public void setup() {
        paperMapper.deleteByDocIdPrefix("WOS:TEST-");
        for (int i = 1; i <= 5; i++) {
            paperMapper.upsert(Paper.builder()
                    .docId("WOS:TEST-Q" + i)
                    .sourceDb(i <= 3 ? "WOS" : "CNKI")
                    .title("LEO Satellite Paper " + i)
                    .firstAuthorCountry(i == 1 ? "CN" : "US")
                    .publishYear(2020 + i)
                    .screeningStatus(i == 5 ? "excluded" : "included")
                    .build());
        }
    }

    @AfterEach
    public void cleanup() {
        paperMapper.deleteByDocIdPrefix("WOS:TEST-");
    }

    @Test
    public void shouldFilterBySourceDb() {
        PaperQueryRequest query = new PaperQueryRequest();
        query.setSourceDb("CNKI");
        List<Paper> papers = paperMapper.selectByCondition(query);
        assertTrue(papers.stream().allMatch(p -> "CNKI".equals(p.getSourceDb())));
        assertEquals(2, papers.size());
        assertEquals(2, paperMapper.countByCondition(query));
    }

    @Test
    public void shouldFilterByYearRangeAndCountry() {
        PaperQueryRequest query = new PaperQueryRequest();
        query.setYearFrom(2022);
        query.setYearTo(2024);
        query.setCountry("US");
        List<Paper> papers = paperMapper.selectByCondition(query);
        // 2022~2024 且 US：Q2(2022) Q3(2023) Q4(2024)
        assertEquals(3, papers.size());
        assertTrue(papers.stream().allMatch(p -> "US".equals(p.getFirstAuthorCountry())));
    }

    @Test
    public void shouldFilterByKeywordInTitleOrAbstract() {
        PaperQueryRequest query = new PaperQueryRequest();
        query.setKeyword("LEO Satellite Paper 3");
        assertEquals(1, paperMapper.selectByCondition(query).size());
    }

    @Test
    public void shouldFilterByScreeningStatus() {
        PaperQueryRequest query = new PaperQueryRequest();
        query.setScreeningStatus("excluded");
        List<Paper> papers = paperMapper.selectByCondition(query);
        assertEquals(1, papers.size());
        assertEquals("WOS:TEST-Q5", papers.get(0).getDocId());
    }

    @Test
    public void shouldPaginate() {
        PaperQueryRequest page1 = new PaperQueryRequest();
        page1.setPage(1);
        page1.setPageSize(2);
        assertEquals(2, paperMapper.selectByCondition(page1).size());

        PaperQueryRequest page3 = new PaperQueryRequest();
        page3.setPage(3);
        page3.setPageSize(2);
        // 共 5 条，第 3 页只余 1 条
        assertEquals(1, paperMapper.selectByCondition(page3).size());
    }

    @Test
    public void emptyQueryShouldReturnAllWithDefaultPaging() {
        PaperQueryRequest query = new PaperQueryRequest(); // page=1, pageSize=20
        assertEquals(5, paperMapper.selectByCondition(query).size());
        assertEquals(5, paperMapper.countByCondition(query));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=PaperMapperQueryTest -q`
Expected: 编译失败 —— `PaperQueryRequest`、`selectByCondition` 不存在

- [ ] **Step 3: 实现 PaperQueryRequest**

创建 `src/main/java/com/kama/jchatmind/model/request/PaperQueryRequest.java`：

```java
package com.kama.jchatmind.model.request;

import lombok.Data;

/**
 * 论文分页查询请求（GET /api/papers 与 Mapper 条件查询共用）
 */
@Data
public class PaperQueryRequest {

    private String sourceDb;

    private String country;

    private Integer yearFrom;

    private Integer yearTo;

    private String screeningStatus;

    /** 标题/摘要关键词（ILIKE 模糊匹配） */
    private String keyword;

    private int page = 1;

    private int pageSize = 20;

    public int getOffset() {
        return Math.max(0, (page - 1) * pageSize);
    }
}
```

- [ ] **Step 4: 扩展 Mapper 接口与 XML**

在 `PaperMapper.java` 接口追加：

```java
    /** 条件分页查询（按 publish_year DESC, doc_id ASC 排序） */
    java.util.List<Paper> selectByCondition(PaperQueryRequest query);

    long countByCondition(PaperQueryRequest query);
```

（import 调整：`com.kama.jchatmind.model.request.PaperQueryRequest`、`java.util.List`。）

在 `PaperMapper.xml` 的 `</mapper>` 前追加：

```xml
    <sql id="Query_Condition">
        <where>
            <if test="sourceDb != null and sourceDb != ''">
                AND source_db = #{sourceDb}
            </if>
            <if test="country != null and country != ''">
                AND first_author_country = #{country}
            </if>
            <if test="yearFrom != null">
                AND publish_year &gt;= #{yearFrom}
            </if>
            <if test="yearTo != null">
                AND publish_year &lt;= #{yearTo}
            </if>
            <if test="screeningStatus != null and screeningStatus != ''">
                AND screening_status = #{screeningStatus}
            </if>
            <if test="keyword != null and keyword != ''">
                AND (title ILIKE CONCAT('%', #{keyword}, '%')
                 OR abstract_text ILIKE CONCAT('%', #{keyword}, '%'))
            </if>
        </where>
    </sql>

    <select id="selectByCondition" parameterType="com.kama.jchatmind.model.request.PaperQueryRequest"
            resultMap="BaseResultMap">
        SELECT id,
               doc_id, source_db, title,
               abstract_text, authors::text AS authors, affiliations::text AS affiliations,
               first_author, first_author_affiliation, first_author_country,
               country_evidence, country_confidence, publish_year,
               journal, doc_type, cited_count,
               doi, keywords, screening_status,
               exclude_reason, file_name, created_at, updated_at
        FROM paper
        <include refid="Query_Condition"/>
        ORDER BY publish_year DESC NULLS LAST, doc_id ASC
        LIMIT #{pageSize} OFFSET #{offset}
    </select>

    <select id="countByCondition" parameterType="com.kama.jchatmind.model.request.PaperQueryRequest"
            resultType="long">
        SELECT COUNT(*)
        FROM paper
        <include refid="Query_Condition"/>
    </select>
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn test -Dtest=PaperMapperQueryTest -q`
Expected: PASS（6 个测试）

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/kama/jchatmind/model/request/PaperQueryRequest.java src/main/java/com/kama/jchatmind/mapper/PaperMapper.java src/main/resources/mapper/PaperMapper.xml src/test/java/com/kama/jchatmind/mapper/PaperMapperQueryTest.java
git commit -m "feat: paper分页条件查询（来源/国别/年份/状态/关键词）"
```

---

### Task 6: 导入编排服务（PaperFacadeService.importMetadata）

**Files:**
- Create: `src/main/java/com/kama/jchatmind/model/response/PaperImportResponse.java`
- Create: `src/main/java/com/kama/jchatmind/service/PaperFacadeService.java`
- Create: `src/main/java/com/kama/jchatmind/service/impl/PaperFacadeServiceImpl.java`
- Test: `src/test/java/com/kama/jchatmind/service/PaperImportServiceTest.java`

**Interfaces:**
- Consumes: Task 3 的 `WosCsvParser#parse(InputStream): List<Paper>`、Task 4 的 `CnkiRefWorksParser#parse(InputStream): List<Paper>`、Task 2 的 `PaperMapper#upsert(Paper): int` / `selectByDocId(String): Paper`
- Produces:
  - `PaperFacadeService#importMetadata(MultipartFile file, String source): PaperImportResponse` —— source 取值 `WOS` / `CNKI`（大小写不敏感），非法值抛 `BizException`
  - `PaperImportResponse`：`sourceDb, total, inserted, updated, failed, errors(List<String>, 上限 20 条)`
  - 导入语义：逐条 `selectByDocId` 判存在 → 不存在 `inserted++`，存在 `updated++` → `upsert`；单条失败（如 docId 冲突、字段超长）记入 `failed` 与 `errors`，不中断整批

- [ ] **Step 1: 写失败的集成测试**

创建 `src/test/java/com/kama/jchatmind/service/PaperImportServiceTest.java`：

```java
package com.kama.jchatmind.service;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.PaperMapper;
import com.kama.jchatmind.model.response.PaperImportResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 论文元数据导入服务集成测试
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
public class PaperImportServiceTest {

    private static final String WOS_CSV =
            "PT,AU,TI,SO,DT,PY,AB,TC,C1,UT,DI\n"
            + "C,\"A, B\",Title One,J1,Article,2020,Abs1,1,\"Inst, CN\",WOS:TEST-IMP1,\n"
            + "C,\"C, D\",Title Two,J2,Article,2021,Abs2,,\"Inst, US\",WOS:TEST-IMP2,10.1/x\n";

    private static final String CNKI_TXT = """
            RT Journal Article
            A1 作者甲;作者乙
            T1 中文测试论文一
            JF 测试期刊
            YR 2024

            """;

    @Autowired
    private PaperFacadeService paperFacadeService;

    @Autowired
    private PaperMapper paperMapper;

    @AfterEach
    public void cleanup() {
        paperMapper.deleteByDocIdPrefix("WOS:TEST-");
        paperMapper.deleteByDocIdPrefix("CNKI:");
    }

    @Test
    public void importWosShouldInsertThenUpdateIdempotently() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "wos.csv", "text/csv", WOS_CSV.getBytes(StandardCharsets.UTF_8));

        PaperImportResponse first = paperFacadeService.importMetadata(file, "WOS");
        assertEquals("WOS", first.getSourceDb());
        assertEquals(2, first.getTotal());
        assertEquals(2, first.getInserted());
        assertEquals(0, first.getUpdated());
        assertEquals(0, first.getFailed());

        // 第二次导入同内容：全部走更新，不产生重复
        PaperImportResponse second = paperFacadeService.importMetadata(file, "WOS");
        assertEquals(2, second.getTotal());
        assertEquals(0, second.getInserted());
        assertEquals(2, second.getUpdated());
        assertEquals(0, second.getFailed());
    }

    @Test
    public void importCnkiShouldConstructDocId() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cnki.txt", "text/plain", CNKI_TXT.getBytes(StandardCharsets.UTF_8));

        PaperImportResponse response = paperFacadeService.importMetadata(file, "CNKI");
        assertEquals(1, response.getInserted());
        assertNotNull(paperMapper.selectByDocId(
                "CNKI:" + com.kama.jchatmind.service.paper.CnkiRefWorksParser.md5Hex("中文测试论文一|测试期刊|2024")));
    }

    @Test
    public void sourceShouldBeCaseInsensitive() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "wos.csv", "text/csv", WOS_CSV.getBytes(StandardCharsets.UTF_8));
        PaperImportResponse response = paperFacadeService.importMetadata(file, "wos");
        assertEquals("WOS", response.getSourceDb());
    }

    @Test
    public void invalidSourceShouldThrowBizException() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.csv", "text/csv", new byte[0]);
        assertThrows(BizException.class, () -> paperFacadeService.importMetadata(file, "INVALID"));
    }

    @Test
    public void emptyFileShouldReturnZeroTotals() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "wos.csv", "text/csv", "PT,AU,TI,UT\n".getBytes(StandardCharsets.UTF_8));
        PaperImportResponse response = paperFacadeService.importMetadata(file, "WOS");
        assertEquals(0, response.getTotal());
        assertEquals(0, response.getInserted());
    }
}
```

注意 `importCnkiShouldConstructDocId` 的清理依赖 `deleteByDocIdPrefix("CNKI:")` 会删除**全部** CNKI 记录——若此时库中已有真实 CNKI 数据会被误删。**修正**：CNKI 测试数据用可控标题，清理改为精确删除该 docId：`paperMapper.deleteByDocIdPrefix(fullDocId)`（前缀匹配等值命中）。实现时在 cleanup 中先计算完整 docId 再按其前缀删除。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=PaperImportServiceTest -q`
Expected: 编译失败 —— `PaperFacadeService` 不存在

- [ ] **Step 3: 实现响应 DTO、服务接口与实现**

先确认 `BizException` 构造签名（`src/main/java/com/kama/jchatmind/exception/BizException.java`，已有类，构造为 `new BizException(String message)`；如签名不同以现有代码为准）。

创建 `src/main/java/com/kama/jchatmind/model/response/PaperImportResponse.java`：

```java
package com.kama.jchatmind.model.response;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 论文元数据导入结果
 */
@Data
@Builder
public class PaperImportResponse {

    private String sourceDb;

    /** 解析出的记录总数 */
    private int total;

    private int inserted;

    private int updated;

    private int failed;

    /** 失败明细（最多保留 20 条） */
    private List<String> errors;
}
```

创建 `src/main/java/com/kama/jchatmind/service/PaperFacadeService.java`：

```java
package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.PaperQueryRequest;
import com.kama.jchatmind.model.response.PaperImportResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 论文元数据门面服务
 */
public interface PaperFacadeService {

    /**
     * 导入论文元数据（幂等：按 doc_id upsert）
     *
     * @param file   上传的源文件（WoS CSV 或 CNKI RefWorks TXT）
     * @param source WOS / CNKI（大小写不敏感）
     */
    PaperImportResponse importMetadata(MultipartFile file, String source);
}
```

创建 `src/main/java/com/kama/jchatmind/service/impl/PaperFacadeServiceImpl.java`：

```java
package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.PaperMapper;
import com.kama.jchatmind.model.entity.Paper;
import com.kama.jchatmind.model.response.PaperImportResponse;
import com.kama.jchatmind.service.PaperFacadeService;
import com.kama.jchatmind.service.paper.CnkiRefWorksParser;
import com.kama.jchatmind.service.paper.WosCsvParser;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 论文元数据导入实现：解析 → 逐条判存在 → upsert → 汇总统计
 */
@Slf4j
@Service
@AllArgsConstructor
public class PaperFacadeServiceImpl implements PaperFacadeService {

    private static final int MAX_ERRORS_IN_RESPONSE = 20;

    private final WosCsvParser wosCsvParser;

    private final CnkiRefWorksParser cnkiRefWorksParser;

    private final PaperMapper paperMapper;

    @Override
    public PaperImportResponse importMetadata(MultipartFile file, String source) {
        if (file == null || file.isEmpty()) {
            throw new BizException("导入文件不能为空");
        }
        String normalizedSource = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);

        List<Paper> papers;
        try (InputStream in = file.getInputStream()) {
            papers = switch (normalizedSource) {
                case "WOS" -> wosCsvParser.parse(in);
                case "CNKI" -> cnkiRefWorksParser.parse(in);
                default -> throw new BizException("不支持的来源库：" + source + "，仅支持 WOS / CNKI");
            };
        } catch (IOException e) {
            throw new BizException("读取导入文件失败：" + e.getMessage());
        }

        int inserted = 0;
        int updated = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (Paper paper : papers) {
            try {
                if (paperMapper.selectByDocId(paper.getDocId()) == null) {
                    inserted++;
                } else {
                    updated++;
                }
                paperMapper.upsert(paper);
            } catch (Exception e) {
                failed++;
                if (errors.size() < MAX_ERRORS_IN_RESPONSE) {
                    errors.add(paper.getDocId() + ": " + e.getMessage());
                }
                log.warn("论文导入失败 docId={}", paper.getDocId(), e);
            }
        }

        log.info("论文元数据导入完成 source={} total={} inserted={} updated={} failed={}",
                normalizedSource, papers.size(), inserted, updated, failed);
        return PaperImportResponse.builder()
                .sourceDb(normalizedSource)
                .total(papers.size())
                .inserted(inserted)
                .updated(updated)
                .failed(failed)
                .errors(errors)
                .build();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=PaperImportServiceTest -q`
Expected: PASS（5 个测试）

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/kama/jchatmind/model/response/PaperImportResponse.java src/main/java/com/kama/jchatmind/service/PaperFacadeService.java src/main/java/com/kama/jchatmind/service/impl/PaperFacadeServiceImpl.java src/test/java/com/kama/jchatmind/service/PaperImportServiceTest.java
git commit -m "feat: 论文元数据导入服务（幂等upsert+统计）"
```

---

### Task 7: 查询服务、统计接口与 PaperController

**Files:**
- Create: `src/main/java/com/kama/jchatmind/model/response/PaperSummary.java`
- Create: `src/main/java/com/kama/jchatmind/model/response/GetPapersResponse.java`
- Create: `src/main/java/com/kama/jchatmind/model/response/GetPaperResponse.java`
- Create: `src/main/java/com/kama/jchatmind/model/response/PaperImportStatsResponse.java`
- Create: `src/main/java/com/kama/jchatmind/controller/PaperController.java`
- Modify: `src/main/java/com/kama/jchatmind/service/PaperFacadeService.java`（追加 3 个方法）
- Modify: `src/main/java/com/kama/jchatmind/service/impl/PaperFacadeServiceImpl.java`（追加实现）
- Modify: `src/main/java/com/kama/jchatmind/mapper/PaperMapper.java`（追加 `countGroupBySourceDb` / `countGroupByScreeningStatus`）
- Modify: `src/main/resources/mapper/PaperMapper.xml`（追加 2 个 group by 查询）
- Test: `src/test/java/com/kama/jchatmind/controller/PaperControllerTest.java`

**Interfaces:**
- Consumes: Task 5 的 `PaperMapper#selectByCondition / countByCondition`、`PaperQueryRequest`；Task 6 的 `PaperFacadeService#importMetadata`
- Produces（REST，SPEC 第 2 节）:
  - `POST /api/papers/import/metadata?source=WOS|CNKI`（multipart 字段 `file`）→ `ApiResponse<PaperImportResponse>`
  - `GET /api/papers?sourceDb=&country=&yearFrom=&yearTo=&screeningStatus=&keyword=&page=1&pageSize=20` → `ApiResponse<GetPapersResponse>`
  - `GET /api/papers/{docId}` → `ApiResponse<GetPaperResponse>`（不存在抛 `BizException`）
  - `GET /api/papers/import/stats` → `ApiResponse<PaperImportStatsResponse>`
  - `PaperMapper#countGroupBySourceDb(): List<Map<String,Object>>`、`countGroupByScreeningStatus(): List<Map<String,Object>>`

- [ ] **Step 1: 写失败的集成测试**

创建 `src/test/java/com/kama/jchatmind/controller/PaperControllerTest.java`：

```java
package com.kama.jchatmind.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.PaperMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 论文元数据接口集成测试（MockMvc）
 *
 * 前置要求：
 * 1. PostgreSQL 运行在 localhost:5432，数据库 jchatmind 已创建
 */
@SpringBootTest
@AutoConfigureMockMvc
public class PaperControllerTest {

    private static final String WOS_CSV =
            "PT,AU,TI,SO,DT,PY,AB,TC,C1,UT,DI\n"
            + "C,\"A, B\",Controller Paper,J1,Article,2023,Abs,1,\"Inst, CN\",WOS:TEST-CTRL1,\n";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaperMapper paperMapper;

    @AfterEach
    public void cleanup() {
        paperMapper.deleteByDocIdPrefix("WOS:TEST-");
    }

    @Test
    public void importThenQueryThenDetailThenStats() throws Exception {
        // 1. 导入
        MockMultipartFile file = new MockMultipartFile(
                "file", "wos.csv", "text/csv", WOS_CSV.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/papers/import/metadata")
                        .file(file)
                        .param("source", "WOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.inserted").value(1));

        // 2. 关键词分页查询
        MvcResult listResult = mockMvc.perform(get("/api/papers")
                        .param("keyword", "Controller Paper")
                        .param("page", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andReturn();

        JsonNode papers = objectMapper.readTree(listResult.getResponse().getContentAsString())
                .at("/data/papers");
        assertEquals(1, papers.size());
        assertEquals("WOS:TEST-CTRL1", papers.get(0).get("docId").asText());
        assertEquals("Controller Paper", papers.get(0).get("title").asText());

        // 3. 详情
        mockMvc.perform(get("/api/papers/WOS:TEST-CTRL1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.docId").value("WOS:TEST-CTRL1"))
                .andExpect(jsonPath("$.data.firstAuthor").value("A, B"));

        // 4. 统计
        mockMvc.perform(get("/api/papers/import/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bySourceDb.WOS").isNotEmpty());
    }

    @Test
    public void detailOfAbsentPaperShouldReturnError() throws Exception {
        mockMvc.perform(get("/api/papers/WOS:TEST-NOT-EXIST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=PaperControllerTest -q`
Expected: 编译失败或 404 —— `PaperController` 不存在

- [ ] **Step 3: 实现响应 DTO**

创建 `src/main/java/com/kama/jchatmind/model/response/PaperSummary.java`：

```java
package com.kama.jchatmind.model.response;

import lombok.Builder;
import lombok.Data;

/**
 * 论文列表条目（分页查询返回）
 */
@Data
@Builder
public class PaperSummary {
    private String docId;
    private String sourceDb;
    private String title;
    private String firstAuthor;
    private String firstAuthorCountry;
    private Integer publishYear;
    private String journal;
    private String docType;
    private Integer citedCount;
    private String screeningStatus;
}
```

创建 `src/main/java/com/kama/jchatmind/model/response/GetPapersResponse.java`：

```java
package com.kama.jchatmind.model.response;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * 论文分页查询响应
 */
@Data
@Builder
public class GetPapersResponse {
    private long total;
    private int page;
    private int pageSize;
    private List<PaperSummary> papers;
}
```

创建 `src/main/java/com/kama/jchatmind/model/response/GetPaperResponse.java`：

```java
package com.kama.jchatmind.model.response;

import lombok.Builder;
import lombok.Data;

/**
 * 论文详情响应（全字段）
 */
@Data
@Builder
public class GetPaperResponse {
    private String docId;
    private String sourceDb;
    private String title;
    private String abstractText;
    private String authors;
    private String affiliations;
    private String firstAuthor;
    private String firstAuthorAffiliation;
    private String firstAuthorCountry;
    private String countryEvidence;
    private String countryConfidence;
    private Integer publishYear;
    private String journal;
    private String docType;
    private Integer citedCount;
    private String doi;
    private String keywords;
    private String screeningStatus;
    private String excludeReason;
    private String fileName;
}
```

创建 `src/main/java/com/kama/jchatmind/model/response/PaperImportStatsResponse.java`：

```java
package com.kama.jchatmind.model.response;

import java.util.Map;

import lombok.Builder;
import lombok.Data;

/**
 * 论文入库统计（GET /api/papers/import/stats）
 */
@Data
@Builder
public class PaperImportStatsResponse {
    /** key=source_db（WOS/CNKI），value=记录数 */
    private Map<String, Long> bySourceDb;
    /** key=screening_status（pending/included/excluded），value=记录数 */
    private Map<String, Long> byScreeningStatus;
    private long total;
}
```

- [ ] **Step 4: 扩展 Mapper（分组统计）**

`PaperMapper.java` 追加：

```java
    /** 按来源库分组计数，返回列 source_db / cnt */
    List<Map<String, Object>> countGroupBySourceDb();

    /** 按筛选状态分组计数，返回列 screening_status / cnt */
    List<Map<String, Object>> countGroupByScreeningStatus();
```

（import `java.util.Map`。）

`PaperMapper.xml` 追加：

```xml
    <select id="countGroupBySourceDb" resultType="map">
        SELECT source_db, COUNT(*) AS cnt
        FROM paper
        GROUP BY source_db
    </select>

    <select id="countGroupByScreeningStatus" resultType="map">
        SELECT screening_status, COUNT(*) AS cnt
        FROM paper
        GROUP BY screening_status
    </select>
```

- [ ] **Step 5: 扩展服务接口与实现**

`PaperFacadeService.java` 追加：

```java
    GetPapersResponse getPapers(PaperQueryRequest query);

    GetPaperResponse getPaper(String docId);

    PaperImportStatsResponse getImportStats();
```

（import 对应响应类型。）

`PaperFacadeServiceImpl.java`：类上追加 `implements` 无变化，追加方法实现与 mapper 字段复用：

```java
    @Override
    public GetPapersResponse getPapers(PaperQueryRequest query) {
        long total = paperMapper.countByCondition(query);
        List<PaperSummary> papers = paperMapper.selectByCondition(query).stream()
                .map(this::toSummary)
                .toList();
        return GetPapersResponse.builder()
                .total(total)
                .page(query.getPage())
                .pageSize(query.getPageSize())
                .papers(papers)
                .build();
    }

    @Override
    public GetPaperResponse getPaper(String docId) {
        Paper paper = paperMapper.selectByDocId(docId);
        if (paper == null) {
            throw new BizException("论文不存在：" + docId);
        }
        return GetPaperResponse.builder()
                .docId(paper.getDocId())
                .sourceDb(paper.getSourceDb())
                .title(paper.getTitle())
                .abstractText(paper.getAbstractText())
                .authors(paper.getAuthors())
                .affiliations(paper.getAffiliations())
                .firstAuthor(paper.getFirstAuthor())
                .firstAuthorAffiliation(paper.getFirstAuthorAffiliation())
                .firstAuthorCountry(paper.getFirstAuthorCountry())
                .countryEvidence(paper.getCountryEvidence())
                .countryConfidence(paper.getCountryConfidence())
                .publishYear(paper.getPublishYear())
                .journal(paper.getJournal())
                .docType(paper.getDocType())
                .citedCount(paper.getCitedCount())
                .doi(paper.getDoi())
                .keywords(paper.getKeywords())
                .screeningStatus(paper.getScreeningStatus())
                .excludeReason(paper.getExcludeReason())
                .fileName(paper.getFileName())
                .build();
    }

    @Override
    public PaperImportStatsResponse getImportStats() {
        Map<String, Long> bySource = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : paperMapper.countGroupBySourceDb()) {
            long cnt = ((Number) row.get("cnt")).longValue();
            bySource.put(String.valueOf(row.get("source_db")), cnt);
            total += cnt;
        }
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> row : paperMapper.countGroupByScreeningStatus()) {
            byStatus.put(String.valueOf(row.get("screening_status")), ((Number) row.get("cnt")).longValue());
        }
        return PaperImportStatsResponse.builder()
                .bySourceDb(bySource)
                .byScreeningStatus(byStatus)
                .total(total)
                .build();
    }

    private PaperSummary toSummary(Paper paper) {
        return PaperSummary.builder()
                .docId(paper.getDocId())
                .sourceDb(paper.getSourceDb())
                .title(paper.getTitle())
                .firstAuthor(paper.getFirstAuthor())
                .firstAuthorCountry(paper.getFirstAuthorCountry())
                .publishYear(paper.getPublishYear())
                .journal(paper.getJournal())
                .docType(paper.getDocType())
                .citedCount(paper.getCitedCount())
                .screeningStatus(paper.getScreeningStatus())
                .build();
    }
```

（import 补充：`com.kama.jchatmind.model.request.PaperQueryRequest`、`GetPapersResponse`、`GetPaperResponse`、`PaperImportStatsResponse`、`PaperSummary`、`java.util.LinkedHashMap`、`java.util.Map`。）

- [ ] **Step 6: 实现 Controller**

创建 `src/main/java/com/kama/jchatmind/controller/PaperController.java`：

```java
package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.PaperQueryRequest;
import com.kama.jchatmind.model.response.GetPaperResponse;
import com.kama.jchatmind.model.response.GetPapersResponse;
import com.kama.jchatmind.model.response.PaperImportResponse;
import com.kama.jchatmind.model.response.PaperImportStatsResponse;
import com.kama.jchatmind.service.PaperFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 论文元数据接口（导入/查询/统计，SPEC 第 2 节）
 */
@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class PaperController {

    private final PaperFacadeService paperFacadeService;

    // 导入论文元数据（幂等，source=WOS/CNKI）
    @PostMapping("/papers/import/metadata")
    public ApiResponse<PaperImportResponse> importMetadata(
            @RequestParam("file") MultipartFile file,
            @RequestParam("source") String source) {
        return ApiResponse.success(paperFacadeService.importMetadata(file, source));
    }

    // 分页查询论文
    @GetMapping("/papers")
    public ApiResponse<GetPapersResponse> getPapers(
            @RequestParam(required = false) String sourceDb,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) Integer yearFrom,
            @RequestParam(required = false) Integer yearTo,
            @RequestParam(required = false) String screeningStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PaperQueryRequest query = new PaperQueryRequest();
        query.setSourceDb(sourceDb);
        query.setCountry(country);
        query.setYearFrom(yearFrom);
        query.setYearTo(yearTo);
        query.setScreeningStatus(screeningStatus);
        query.setKeyword(keyword);
        query.setPage(page);
        query.setPageSize(Math.min(pageSize, 200));
        return ApiResponse.success(paperFacadeService.getPapers(query));
    }

    // 论文详情
    @GetMapping("/papers/{docId}")
    public ApiResponse<GetPaperResponse> getPaper(@PathVariable String docId) {
        return ApiResponse.success(paperFacadeService.getPaper(docId));
    }

    // 入库统计
    @GetMapping("/papers/import/stats")
    public ApiResponse<PaperImportStatsResponse> getImportStats() {
        return ApiResponse.success(paperFacadeService.getImportStats());
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn test -Dtest=PaperControllerTest -q`
Expected: PASS（2 个测试）

- [ ] **Step 8: 运行本计划全部测试回归**

Run: `mvn test -Dtest='PaperSchemaInitializerTest,PaperMapperUpsertTest,WosCsvParserTest,CnkiRefWorksParserTest,PaperMapperQueryTest,PaperImportServiceTest,PaperControllerTest' -q`
Expected: 全部 PASS（24 个测试）

- [ ] **Step 9: 提交**

```bash
git add src/main/java/com/kama/jchatmind/model/response/PaperSummary.java src/main/java/com/kama/jchatmind/model/response/GetPapersResponse.java src/main/java/com/kama/jchatmind/model/response/GetPaperResponse.java src/main/java/com/kama/jchatmind/model/response/PaperImportStatsResponse.java src/main/java/com/kama/jchatmind/controller/PaperController.java src/main/java/com/kama/jchatmind/service/PaperFacadeService.java src/main/java/com/kama/jchatmind/service/impl/PaperFacadeServiceImpl.java src/main/java/com/kama/jchatmind/mapper/PaperMapper.java src/main/resources/mapper/PaperMapper.xml src/test/java/com/kama/jchatmind/controller/PaperControllerTest.java
git commit -m "feat: 论文元数据REST接口（导入/分页查询/详情/统计）"
```

---

### Task 8: 真实数据导入验证

**Files:**
- 无新代码；产出验证记录 `docs/superpowers/plans/2026-08-25-paper-metadata-import-verification.md`

**Interfaces:**
- Consumes: Task 7 的全部 REST 接口
- Produces: 开发库中导入全量 WoS + CNKI 元数据；验证记录文档

- [ ] **Step 1: 启动应用**

Run: `mvn spring-boot:run`（保持运行；如 application.yaml 配置了非默认端口，以下命令端口以配置为准）

- [ ] **Step 2: 记录源文件记录数基准**

```bash
grep -c "^PT " "C:/研究生阶段文档/文献情报中心资料/小论文/WOS_data.txt"
grep -c "^RT " "C:/研究生阶段文档/文献情报中心资料/小论文/知网论文数据.txt"
```

记下两个数字（WoS ≈ 5628，CNKI 以实际输出为准）。

- [ ] **Step 3: 导入 WoS 真实数据并核对**

```bash
curl -s -X POST "http://localhost:8080/api/papers/import/metadata?source=WOS" \
  -F "file=@C:/研究生阶段文档/文献情报中心资料/小论文/WOS_data.csv"
```

核对：响应 `total` 与 Step 2 的 WoS 基准一致（如不一致，检查 CSV 尾部空行/差异并记录）；`failed` 为 0 或错误明细可解释；`inserted + updated = total`。

- [ ] **Step 4: 导入 CNKI 真实数据并核对**

```bash
curl -s -X POST "http://localhost:8080/api/papers/import/metadata?source=CNKI" \
  -F "file=@C:/研究生阶段文档/文献情报中心资料/小论文/知网论文数据.txt"
```

核对同上。

- [ ] **Step 5: 幂等重跑验证**

再次执行 Step 3 命令，核对响应 `inserted=0`、`updated=total`。

- [ ] **Step 6: 统计与抽查**

```bash
curl -s "http://localhost:8080/api/papers/import/stats"
curl -s "http://localhost:8080/api/papers?sourceDb=WOS&yearFrom=2020&yearTo=2025&page=1&pageSize=5"
curl -s "http://localhost:8080/api/papers?keyword=LEO%20satellite&page=1&pageSize=5"
```

抽查 2-3 条详情接口返回的字段与源文件记录一致（标题、作者、机构、年份、被引）。

- [ ] **Step 7: 写验证记录并提交**

创建 `docs/superpowers/plans/2026-08-25-paper-metadata-import-verification.md`，内容包含：源基准数、两次导入响应 JSON、统计响应、抽查结论、遇到的偏差与解释。

```bash
git add docs/superpowers/plans/2026-08-25-paper-metadata-import-verification.md
git commit -m "docs: 论文元数据真实导入验证记录"
```

---

## Self-Review 结果

**Spec coverage**（对照 SPEC 实施顺序 ① 范围）:
- paper 表与幂等 DDL → Task 1 ✓
- WoS/CNKI 元数据导入（upsert by doc_id）→ Task 3/4/6 ✓
- 分页查询 / 详情 / 统计接口 → Task 5/7 ✓
- BOM、引号逗号、多值字段处理 → Task 3/4 测试覆盖 ✓
- 筛选结论（screening）、分类树、参数证据导入 → 属第二步计划（SPEC ②），本计划仅预留 `screening_status` 等列与 COALESCE 保护 ✓
- 论文全文切分入库 → 属第三步计划（SPEC ③）✓

**Placeholder scan**: 无 TBD/TODO；所有代码步骤含完整代码。计划中两处"修正"注记（Task 1 的 @Component/@Bean 二选一、Task 6 的 CNKI 清理精确化）已给出明确结论，实现时按修正后版本执行。

**Type consistency**: `PaperMapper.upsert(Paper)/selectByDocId(String)/selectByCondition(PaperQueryRequest)/countByCondition(PaperQueryRequest)/deleteByDocIdPrefix(String)` 在 Task 2/5/6/7 间一致；`PaperFacadeService.importMetadata(MultipartFile, String)` 在 Task 6/7 间一致；`WosCsvParser.parse(InputStream)` / `CnkiRefWorksParser.parse(InputStream)` / `CnkiRefWorksParser.md5Hex(String)` 在 Task 3/4/6 间一致。
