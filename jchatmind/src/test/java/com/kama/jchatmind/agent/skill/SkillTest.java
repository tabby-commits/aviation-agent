package com.kama.jchatmind.agent.skill;

import com.kama.jchatmind.agent.skill.model.SkillDefinition;
import com.kama.jchatmind.agent.skill.parser.SkillMdParser;
import com.kama.jchatmind.agent.skill.scanner.SkillScanner;
import com.kama.jchatmind.agent.skill.service.SkillService;
import com.kama.jchatmind.agent.tools.ToolType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.kama.jchatmind.agent.tools.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Skill 功能测试类
 */
class SkillTest {

    @TempDir
    Path tempDir;

    private SkillMdParser parser;
    private SkillScanner scanner;
    private SkillService skillService;
    private SkillTool skillTool;

    @BeforeEach
    void setUp() {
        parser = new SkillMdParser();
        scanner = new SkillScanner(parser);
    }

    @Test
    @DisplayName("测试 SkillMdParser 解析 SKILL.md 文件")
    void testSkillMdParser() throws IOException {
        // 创建测试 SKILL.md 文件
        Path skillDir = tempDir.resolve("code-reviewer");
        Files.createDirectories(skillDir);

        String content = """
                ---
                name: code-reviewer
                description: Java 代码审查 Skill，负责检查代码质量和最佳实践
                ---
                # Code Reviewer Skill

                ## Instructions

                你是一个专业的 Java 代码审查员。当被要求审查代码时：

                1. 检查代码是否符合 Java 编码规范
                2. 识别潜在的安全漏洞
                3. 提出性能优化建议
                4. 确保异常处理得当

                ## 检查清单

                - [ ] 空指针检查
                - [ ] 资源关闭（try-with-resources）
                - [ ] 线程安全
                """;
        Path skillFile = skillDir.resolve("SKILL.md");
        Files.writeString(skillFile, content);

        // 解析文件
        SkillDefinition skill = parser.parse(skillFile);

        // 验证解析结果
        assertNotNull(skill);
        assertEquals("code-reviewer", skill.getName());
        assertEquals("Java 代码审查 Skill，负责检查代码质量和最佳实践", skill.getDescription());
        assertNotNull(skill.getInstructions());
        assertTrue(skill.getInstructions().contains("Code Reviewer Skill"));
        assertTrue(skill.getInstructions().contains("Instructions"));
        assertEquals(skillFile, skill.getSkillPath());
        assertEquals(skillDir, skill.getSkillDirectory());
    }

    @Test
    @DisplayName("测试 SkillMdParser 解析无 FrontMatter 的文件")
    void testSkillMdParserWithoutFrontMatter() throws IOException {
        Path skillDir = tempDir.resolve("simple-skill");
        Files.createDirectories(skillDir);

        String content = """
                # Simple Skill

                这是一个简单的 Skill，只有内容没有元数据。

                ## Instructions

                执行简单的任务。
                """;
        Path skillFile = skillDir.resolve("SKILL.md");
        Files.writeString(skillFile, content);

        // 解析文件 - 应使用目录名作为默认名称
        SkillDefinition skill = parser.parse(skillFile);

        assertNotNull(skill);
        assertEquals("simple-skill", skill.getName()); // 默认使用目录名
        assertEquals("", skill.getDescription());
        assertTrue(skill.getInstructions().contains("Simple Skill"));
    }

    @Test
    @DisplayName("测试 SkillScanner 扫描目录")
    void testSkillScanner() throws IOException {
        // 创建多个 Skill 目录
        Path skillDir1 = tempDir.resolve("skill-one");
        Path skillDir2 = tempDir.resolve("skill-two");
        Files.createDirectories(skillDir1);
        Files.createDirectories(skillDir2);

        // 创建 SKILL.md 文件
        Files.writeString(skillDir1.resolve("SKILL.md"), """
                ---
                name: skill-one
                description: 第一个 Skill
                ---
                # Skill One
                """);

        Files.writeString(skillDir2.resolve("SKILL.md"), """
                ---
                name: skill-two
                description: 第二个 Skill
                ---
                # Skill Two
                """);

        // 扫描目录
        List<SkillDefinition> skills = scanner.scan(tempDir);

        assertEquals(2, skills.size());
        assertTrue(skills.stream().anyMatch(s -> s.getName().equals("skill-one")));
        assertTrue(skills.stream().anyMatch(s -> s.getName().equals("skill-two")));
    }

    @Test
    @DisplayName("测试 SkillScanner 扫描不存在的目录")
    void testSkillScannerWithInvalidDirectory() {
        Path nonExistDir = tempDir.resolve("non-exist");

        List<SkillDefinition> skills = scanner.scan(nonExistDir);

        assertTrue(skills.isEmpty());
    }

    @Test
    @DisplayName("测试 SkillScanner 扫描多个目录")
    void testSkillScannerMultipleDirectories() throws IOException {
        Path skillDir1 = tempDir.resolve("dir1/skill-a");
        Path skillDir2 = tempDir.resolve("dir2/skill-b");
        Files.createDirectories(skillDir1);
        Files.createDirectories(skillDir2);

        Files.writeString(skillDir1.resolve("SKILL.md"), """
                ---
                name: skill-a
                description: Skill A
                ---
                # Skill A
                """);

        Files.writeString(skillDir2.resolve("SKILL.md"), """
                ---
                name: skill-b
                description: Skill B
                ---
                # Skill B
                """);

        List<SkillDefinition> skills = scanner.scanAll(List.of(
                tempDir.resolve("dir1"),
                tempDir.resolve("dir2")
        ));

        assertEquals(2, skills.size());
    }

    @Test
    @DisplayName("测试 SkillDefinition 构建器")
    void testSkillDefinitionBuilder() {
        SkillDefinition skill = SkillDefinition.builder()
                .name("test-skill")
                .description("测试 Skill")
                .instructions("# Test\n\nInstructions here")
                .skillPath(Path.of("/path/to/SKILL.md"))
                .skillDirectory(Path.of("/path/to"))
                .frontMatter("name: test-skill\ndescription: 测试 Skill")
                .rawContent("---\nname: test-skill\n---\n# Test")
                .lastModified(System.currentTimeMillis())
                .build();

        assertEquals("test-skill", skill.getName());
        assertEquals("测试 Skill", skill.getDescription());
        assertNotNull(skill.getInstructions());
        assertNotNull(skill.getSkillPath());
        assertNotNull(skill.getSkillDirectory());
    }

    @Test
    @DisplayName("测试 Skill 解析内容包含特殊字符")
    void testSkillParserWithSpecialCharacters() throws IOException {
        Path skillDir = tempDir.resolve("special-chars");
        Files.createDirectories(skillDir);

        String content = """
                ---
                name: special-skill
                description: 包含特殊字符的 Skill
                ---
                # Special Skill

                测试特殊字符：中文、emoji 🎉、代码 `var x = 1`

                ```java
                public class Test {
                    private String name = "测试";
                }
                ```
                """;
        Path skillFile = skillDir.resolve("SKILL.md");
        Files.writeString(skillFile, content);

        SkillDefinition skill = parser.parse(skillFile);

        assertNotNull(skill);
        assertEquals("special-skill", skill.getName());
        assertTrue(skill.getInstructions().contains("中文"));
        assertTrue(skill.getInstructions().contains("emoji"));
        assertTrue(skill.getInstructions().contains("public class Test"));
    }

    @Test
    @DisplayName("测试解析字符串内容而非文件路径")
    void testParseStringContent() {
        String content = """
                ---
                name: string-parse
                description: 从字符串解析
                ---
                # String Parse Test

                这是一个从字符串内容解析的测试。
                """;

        SkillDefinition skill = parser.parse(content, Path.of("test/SKILL.md"));

        assertNotNull(skill);
        assertEquals("string-parse", skill.getName());
        assertEquals("从字符串解析", skill.getDescription());
        assertTrue(skill.getInstructions().contains("String Parse Test"));
    }

    @Test
    @DisplayName("测试 SkillTool 实现 Tool 接口")
    void testSkillToolImplementsToolInterface() {
        // 验证 SkillTool 实现了 Tool 接口
        assertTrue(Tool.class.isAssignableFrom(SkillTool.class));
    }

    @Test
    @DisplayName("测试 SkillTool 是固定工具")
    void testSkillToolIsFixedTool() {
        assertEquals(ToolType.FIXED, new SkillTool(null).getType());
    }

    @Test
    @DisplayName("测试 invokeSkill 描述提示航天情报分析 Skill")
    void testInvokeSkillDescriptionMentionsSpaceTechSkill() throws NoSuchMethodException {
        org.springframework.ai.tool.annotation.Tool annotation = SkillTool.class
                .getMethod("invokeSkill", String.class, String.class)
                .getAnnotation(org.springframework.ai.tool.annotation.Tool.class);

        assertNotNull(annotation);
        assertTrue(annotation.description().contains("space-tech-intelligence-analyst"));
        assertTrue(annotation.description().contains("航天"));
        assertTrue(annotation.description().contains("竞争力"));
    }

    @Test
    @DisplayName("测试多个 Skill 扫描顺序")
    void testScanMultipleSkillsOrder() throws IOException {
        // 创建三个 Skill
        for (int i = 1; i <= 3; i++) {
            Path skillDir = tempDir.resolve("skill-" + i);
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: skill-%d
                    description: Skill %d
                    ---
                    # Skill %d
                    """.formatted(i, i, i));
        }

        List<SkillDefinition> skills = scanner.scan(tempDir);

        assertEquals(3, skills.size());
        // 验证所有 Skill 都被扫描到
        assertTrue(skills.stream().anyMatch(s -> s.getName().equals("skill-1")));
        assertTrue(skills.stream().anyMatch(s -> s.getName().equals("skill-2")));
        assertTrue(skills.stream().anyMatch(s -> s.getName().equals("skill-3")));
    }

    @Test
    @DisplayName("测试航天科技情报分析 Skill 已注册")
    void testSpaceTechIntelligenceAnalystSkillRegistered() {
        List<SkillDefinition> skills = scanner.scan(Path.of("skills"));

        SkillDefinition skill = skills.stream()
                .filter(s -> s.getName().equals("space-tech-intelligence-analyst"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到航天科技情报分析 Skill"));

        assertTrue(skill.getDescription().contains("航天科技情报分析"));
        assertTrue(skill.getInstructions().contains("战略重要性"));
        assertTrue(skill.getInstructions().contains("技术前瞻性"));
        assertTrue(skill.getInstructions().contains("产业成熟度"));
        assertTrue(skill.getInstructions().contains("全球技术影响力变化"));
    }
}
