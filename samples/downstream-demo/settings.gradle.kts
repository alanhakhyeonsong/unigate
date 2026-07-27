// unigate 본체와 독립된 Gradle 빌드다.
// unigate 의 settings.gradle.kts 는 samples/ 를 include 하지 않으므로
// 루트에서 ./gradlew build 를 돌려도 이 샘플은 영향을 주지 않는다.
rootProject.name = "downstream-demo"
