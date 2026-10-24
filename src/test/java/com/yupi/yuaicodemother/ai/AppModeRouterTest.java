package com.yupi.yuaicodemother.ai;

import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「自动识别」的规则路由单测：不依赖模型、不依赖 Spring，纯函数。
 */
class AppModeRouterTest {

    private final AppModeRouter router = new AppModeRouter();

    @Test
    void storageNeedsAreFullstack() {
        assertEquals(CodeGenTypeEnum.FULLSTACK, router.route("做一个记账本，记录每一笔开销，能按分类查看"));
        assertEquals(CodeGenTypeEnum.FULLSTACK, router.route("做一个待办清单，可以勾选完成"));
        assertEquals(CodeGenTypeEnum.FULLSTACK, router.route("做一个博客，可以发文章和评论"));
        assertEquals(CodeGenTypeEnum.FULLSTACK, router.route("做一个库存管理系统，需要登录"));
    }

    @Test
    void displayPagesAreStatic() {
        assertEquals(CodeGenTypeEnum.STATIC, router.route("做一个公司官网首页，展示产品和服务"));
        assertEquals(CodeGenTypeEnum.STATIC, router.route("做一个番茄钟计时器，可以开始暂停重置"));
        assertEquals(CodeGenTypeEnum.STATIC, router.route("做一个产品落地页，带价格对比"));
    }

    @Test
    void blankFallsBackToStatic() {
        assertEquals(CodeGenTypeEnum.STATIC, router.route(""));
        assertEquals(CodeGenTypeEnum.STATIC, router.route(null));
    }
}
