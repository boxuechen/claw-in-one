package ai.clawinone.androiduse.fixture

import org.junit.Assert.assertEquals
import org.junit.Test

class FixtureContractTest {
  @Test
  fun shortSmoke_hasDistinctInitialAndVerifiedValues() {
    assertEquals("Smoke count: 0", smokeCountLabel(0))
    assertEquals("Smoke count: 1", smokeCountLabel(1))
  }

  @Test
  fun acceptanceResult_preservesChineseTextAndSelectedState() {
    val result =
      FixtureResult(
        taskName = "你好，Android",
        verificationEnabled = true,
        projectNumber = ACCEPTANCE_PROJECT_NUMBER,
      )

    assertEquals(
      "结果：任务名称=你好，Android；验证=开启；项目=20",
      result.render(verificationOn = "开启", verificationOff = "关闭"),
    )
  }
}
