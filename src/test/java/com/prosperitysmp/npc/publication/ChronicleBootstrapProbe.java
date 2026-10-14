package com.prosperitysmp.npc.publication;

import java.util.Arrays;

/** Fresh-JVM, JDK-only probe: no server, token, SQL, or HTTP request. */
public final class ChronicleBootstrapProbe {
    private ChronicleBootstrapProbe() { }
    private static String[] evidence() throws Exception {
        Class<?> agent=Class.forName("com.prosperitysmp.npc.publication.bootstrap.ChronicleBootstrap",false,ClassLoader.getSystemClassLoader());
        if(agent.getClassLoader()!=ClassLoader.getSystemClassLoader()) throw new AssertionError("Wrong loader");
        return (String[])agent.getMethod("evidence").invoke(null);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Expected clean, held, revoke, or paper");
        String[] first=evidence();
        if(args[0].equals("held")) {
            if(first.length!=0) throw new AssertionError("Dirty startup provided proof");
            System.out.println("HELD: no bootstrap proof"); return;
        }
        if(first.length!=11 || !Arrays.equals(first,evidence())) {
            Class<?> agent=Class.forName("com.prosperitysmp.npc.publication.bootstrap.ChronicleBootstrap",false,ClassLoader.getSystemClassLoader());
            throw new AssertionError("Clean proof unavailable or unstable: "+agent.getMethod("diagnosticStatus").invoke(null));
        }
        first[0]="changed-by-caller";
        if(evidence()[0].equals(first[0])) throw new AssertionError("Mutable proof escaped");
        if(args[0].equals("paper")) {
            System.setProperty("java.util.logging.manager","io.papermc.paper.log.CustomLogManager");
            if(!java.util.logging.LogManager.getLogManager().getClass().getName().equals("io.papermc.paper.log.CustomLogManager"))
                throw new AssertionError("Actual Paper manager unavailable; include the installed Paper runtime jar");
            if(evidence().length!=11) throw new AssertionError("Verified Paper manager transition revoked proof");
            System.setProperty("java.util.logging.manager","java.util.logging.LogManager");
            if(evidence().length!=0) throw new AssertionError("Unapproved manager change retained proof");
            System.out.println("PASS: pinned Paper manager transition; unapproved manager rejected");
        } else if(args[0].equals("revoke")) {
            System.setProperty("java.util.logging.config.file","changed-after-startup");
            if(evidence().length!=0) throw new AssertionError("Changed logging path retained proof");
            System.out.println("HELD: changed logging path revoked proof");
        } else if(args[0].equals("clean")) {
            System.out.println("PASS: clean process proof, system-loader bridge, immutable replay");
        } else throw new IllegalArgumentException("Unknown probe mode");
    }
}
