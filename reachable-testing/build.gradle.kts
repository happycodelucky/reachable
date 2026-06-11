/*
 * Reachable — :reachable-testing module.
 *
 * Public, scriptable test fakes and helpers for consumers of `:reachable`:
 * `FakeReachability` plus the `withFakeReachability { … }` helper. Same
 * module shape as `:reachable` via the `reachable.kmp-library` convention
 * plugin; published in lockstep (same group / version / pipeline) via
 * `reachable.publish`. Consumers wire it on `testImplementation` (or KMP
 * `commonTest` deps); the production `:reachable` artifact does not depend
 * on this module.
 *
 * No XCFramework and no SKIE `produceDistributableFramework()`: test code
 * is consumed as KMP klibs from Maven Central, not via SPM. The Apple
 * targets exist so KMP consumers can resolve this module from their Apple
 * test source sets, but we don't ship a binary framework for it.
 */

plugins {
    id("reachable.kmp-library")
    id("reachable.publish")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // `api` so consumers writing `testImplementation(reachable-testing)`
            // get `Reachability` / `ReachabilityStatus` / `Transport`
            // transitively — they will assert against those types.
            api(project(":reachable"))

            // StateFlow plumbing and the atomic counter inside FakeReachability.
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }

        // androidHostTest source set is created by the convention plugin's
        // withHostTestBuilder. Configure its deps here.
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}

mavenPublishing {
    pom {
        name.set("Reachable Testing")
        description.set(
            "Test fakes and helpers for the Reachable KMP library: " +
                "FakeReachability + withFakeReachability for installing it " +
                "as Reachability.shared for the lifetime of a test.",
        )
    }
}
