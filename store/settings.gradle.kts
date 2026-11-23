/*
 * SPDX-FileCopyrightText: 2021-2025 Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-FileCopyrightText: 2022-2025 The Calyx Institute
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libsu, and the Morphe patcher's ARSCLib/smali, are only available via jitpack
        maven("https://jitpack.io/") {
            content {
                includeModule("com.github.topjohnwu.libsu", "core")
                includeGroupByRegex("com\\.github\\.MorpheApp.*")
            }
        }
        // morphe-patcher, built from source and published locally (patches/README)
        mavenLocal {
            content { includeGroup("app.morphe") }
        }
        // Only included in huawei variants
        maven("https://developer.huawei.com/repo/") {
            content {
                includeGroup("com.huawei.hms")
                includeGroup("com.huawei.android.hms")
            }
        }
    }
}
include(":app")
rootProject.name = "AuroraStore4"
