package com.vincegosoftware.fsmeditor

/** The scenarios of `plugin/src/test/scenarios.md` (their `###` ids) a test implements; [ScenariosTest] checks them. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Scenario(vararg val ids: String)
