package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.publication.ChronicleOutboxRepository.Held;
import com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.*;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One off-owner send attempt. Runtime authorization and authenticated transport are mandatory adapters. */
public final class ChronicleDispatcher {
    @FunctionalInterface public interface Authorization {
        /** Exact event/payload, generation and destination policy. Failure must throw or return false. */
        boolean permits(Held held,UUID generation,Destination destination);
    }
    @FunctionalInterface public interface Transport {
        /** Exactly one POST, wait=true, bounded response, no redirect or automatic POST retry. */
        Reply post(Destination destination,Message message) throws IOException,InterruptedException;
    }
    public static final class Reply {
        private final int status;
        private final byte[] body;
        public Reply(int status,byte[] body) {
            if(body!=null && body.length>32768)throw new IllegalArgumentException("Oversized Chronicle response");
            this.status=status;this.body=body==null ? null : body.clone();
        }
        int status(){return status;}
        byte[] body(){return body==null ? null : body.clone();}
    }
    public enum Result { NOT_AUTHORIZED, ALREADY_RESERVED, HELD_BEFORE_POST, UNCONFIRMED, ACKNOWLEDGED }
    private final Thread owner=Thread.currentThread();
    private final ChronicleSendIntentRepository intents;
    private final Authorization authorization;
    private final Transport transport;
    private final AtomicBoolean running=new AtomicBoolean(),cancelled=new AtomicBoolean();
    public ChronicleDispatcher(ChronicleSendIntentRepository intents,Authorization authorization,Transport transport) {
        this.intents=Objects.requireNonNull(intents);this.authorization=Objects.requireNonNull(authorization);this.transport=Objects.requireNonNull(transport);
    }
    /** Cooperative revocation only; a request that has passed its final check may already be in flight. */
    public void cancel(){cancelled.set(true);}
    boolean ownsTransport(Object candidate){return transport==candidate;}

    public Result send(Held held,UUID generation,Destination destination) throws SQLException {
        Objects.requireNonNull(held);Objects.requireNonNull(generation);Objects.requireNonNull(destination);
        if(Thread.currentThread()==owner)throw new IllegalStateException("Chronicle dispatch must run off owner");
        if(!running.compareAndSet(false,true))throw new IllegalStateException("Chronicle dispatch already running");
        try {
            if(!allowed(held,generation,destination))return Result.NOT_AUTHORIZED;
            var reserved=intents.reserve(held,generation,destination);
            if(reserved.isEmpty())return Result.ALREADY_RESERVED;
            var intent=reserved.get();
            // Leave the durable INTENT held if approval disappears. Do not delete it or create retry permission.
            if(!allowed(held,generation,destination))return Result.HELD_BEFORE_POST;
            Reply reply;
            try{reply=Objects.requireNonNull(transport.post(intent.destination(),intent.message()));}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();return Result.UNCONFIRMED;}
            catch(IOException | RuntimeException uncertain){intents.uncertain(intent);return Result.UNCONFIRMED;}
            var receipt=ChronicleWebhookProtocol.confirmed(reply.status,reply.body,intent.destination(),intent.message());
            if(receipt.isEmpty()){intents.uncertain(intent);return Result.UNCONFIRMED;}
            // Revocation cannot erase an already sent message. Always try to retain its receipt, even after cancellation.
            intents.acknowledge(intent,receipt.get());return Result.ACKNOWLEDGED;
        }finally{running.set(false);}
    }
    private boolean allowed(Held held,UUID generation,Destination destination) {
        return !cancelled.get() && authorization.permits(held,generation,destination) && !cancelled.get();
    }
}
