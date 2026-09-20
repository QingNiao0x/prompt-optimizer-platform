package com.promptoptimizer.identity.support;

import com.promptoptimizer.identity.application.ActorIdentity;
import com.promptoptimizer.identity.application.CurrentActor;

import java.util.UUID;

/**
 * 单元测试使用的稳定认证主体。
 */
public final class TestActors {

    public static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    public static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    public static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");

    private TestActors() {
    }

    public static CurrentActor currentActor() {
        return () -> identity(USER_ID);
    }

    public static ActorIdentity identity(UUID userId) {
        return new ActorIdentity(
                userId,
                TENANT_ID,
                WORKSPACE_ID,
                "test@local",
                "Test User"
        );
    }
}
