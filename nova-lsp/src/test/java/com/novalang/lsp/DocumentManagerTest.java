package com.novalang.lsp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DocumentManager 测试")
class DocumentManagerTest {

    private DocumentManager manager;

    @BeforeEach
    void setUp() {
        manager = new DocumentManager();
    }

    @Test
    @DisplayName("打开文档后可获取内容")
    void testOpenAndGetContent() {
        manager.open("file:///test.nova", "val x = 1");
        assertThat(manager.getContent("file:///test.nova")).isEqualTo("val x = 1");
        assertThat(manager.isOpen("file:///test.nova")).isTrue();
    }

    @Test
    @DisplayName("未打开的文档返回 null")
    void testGetContentNotOpened() {
        assertThat(manager.getContent("file:///unknown.nova")).isNull();
        assertThat(manager.isOpen("file:///unknown.nova")).isFalse();
    }

    @Test
    @DisplayName("更新文档内容")
    void testChange() {
        manager.open("file:///test.nova", "val x = 1");
        manager.change("file:///test.nova", "val x = 2");
        assertThat(manager.getContent("file:///test.nova")).isEqualTo("val x = 2");
    }

    @Test
    @DisplayName("关闭文档后不可获取")
    void testClose() {
        manager.open("file:///test.nova", "val x = 1");
        manager.close("file:///test.nova");
        assertThat(manager.getContent("file:///test.nova")).isNull();
        assertThat(manager.isOpen("file:///test.nova")).isFalse();
    }

    @Test
    @DisplayName("从 URI 提取文件名")
    void testGetFileName() {
        assertThat(DocumentManager.getFileName("file:///home/user/test.nova")).isEqualTo("test.nova");
        assertThat(DocumentManager.getFileName("file:///C:/projects/app.nova")).isEqualTo("app.nova");
        assertThat(DocumentManager.getFileName("test.nova")).isEqualTo("test.nova");
    }

    @Test
    @DisplayName("管理多个文档")
    void testMultipleDocuments() {
        manager.open("file:///a.nova", "val a = 1");
        manager.open("file:///b.nova", "val b = 2");

        assertThat(manager.getContent("file:///a.nova")).isEqualTo("val a = 1");
        assertThat(manager.getContent("file:///b.nova")).isEqualTo("val b = 2");

        manager.close("file:///a.nova");
        assertThat(manager.isOpen("file:///a.nova")).isFalse();
        assertThat(manager.isOpen("file:///b.nova")).isTrue();
    }
}
