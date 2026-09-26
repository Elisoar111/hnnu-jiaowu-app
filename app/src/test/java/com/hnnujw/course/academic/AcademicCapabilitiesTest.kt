package com.hnnujw.course.academic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 能力表的对外契约：只支持正方（淮南师范学院），账号上限 3 个。 */
class AcademicCapabilitiesTest {
    @Test fun supportNamesCoverTheSingleSupportedSchool() {
        assertEquals(setOf(AcademicSystem.ZF), AcademicCapabilities.systems.map { it.system }.toSet())
        assertEquals("正方教务（淮南师范学院）", AcademicCapabilities.name("zf"))
        assertTrue(AcademicCapabilities.ACCOUNT_LIMIT.contains("3"))
    }
}
