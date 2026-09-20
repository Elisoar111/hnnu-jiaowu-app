package com.hnnujw.course.academic

import com.hnnujw.course.model.SchoolConfig
import org.junit.Assert.*
import org.junit.Test

class AcademicGatewayReadinessTest {
    @Test fun everyConfigurationUsesTheSingleSupportedAdapter() {
        val school = SchoolConfig("hnnu", "淮南师范学院", "jwgl.hnnu.edu.cn", "https")
        for (type in listOf("zf", "unknown", "")) {
            school.academicSystem = type
            assertTrue(AcademicGatewayFactory.supports(school))
            assertTrue(AcademicGatewayFactory.hasSelectedAdapter(school))
        }
    }

    @Test fun theOnlyLoginEntryIsTheZhengfangDirectLoginPage() {
        val school = SchoolConfig("hnnu", "淮南师范学院", "jwgl.hnnu.edu.cn", "https").apply { basePath = "/jwglxt" }
        assertEquals("https://jwgl.hnnu.edu.cn/jwglxt/xtgl/login_slogin.html", AcademicGatewayFactory.loginUrl(school))
    }
}
