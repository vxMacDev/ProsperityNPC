package com.prosperitysmp.npc.api.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names one contract case, for example "MS-02". A critical case must have a deliberately broken subject that it
 * catches (see {@link ContractMeta}); a registry test checks that every critical case has one.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CaseId {
    String value();

    boolean critical() default false;
}
