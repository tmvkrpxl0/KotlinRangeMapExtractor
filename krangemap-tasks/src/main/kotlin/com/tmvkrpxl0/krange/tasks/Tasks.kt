package com.tmvkrpxl0.krange.tasks

import net.minecraftforge.gradle.common.tasks.ApplyRangeMap
import net.minecraftforge.gradle.common.tasks.ExtractRangeMap

val FATJAR = "com.tmvkrpxl0:krangemap:1.+:fatJar"

abstract class ExtractKRangeMap : ExtractRangeMap() {
    init {
        tool.set(FATJAR)
    }
}

abstract class ApplyKRangeMap : ApplyRangeMap() {
    init {
        tool.set(FATJAR)
    }
}