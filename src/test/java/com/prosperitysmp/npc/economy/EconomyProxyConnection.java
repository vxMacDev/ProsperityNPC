package com.prosperitysmp.npc.economy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * A JDBC connection that answers only getAutoCommit and fails the test on any other call, so a test can prove a
 * refusal happens before any SQL is prepared. Every method name asked for is recorded.
 */
final class EconomyProxyConnection {
    final List<String> calls = new ArrayList<>();
    final Connection connection;

    EconomyProxyConnection(boolean autoCommit) {
        connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    return switch (method.getName()) {
                        case "getAutoCommit" -> autoCommit;
                        case "toString" -> "economy-proxy-connection";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new AssertionError("unexpected connection call " + method.getName());
                    };
                });
    }
}
