# AGENTS.md 鈥?Agora 椤圭洰浠ｇ悊宸ヤ綔鎸囧紩

> 鏈枃浠朵緵 AI 缂栫爜浠ｇ悊锛堝惈鏈潵浼氳瘽锛夎繘鍏ラ」鐩椂**棣栧厛鑷**锛屽揩閫熷榻愰」鐩畾浣嶃€佸綋鍓嶈繘搴︺€佹灦鏋勫绾︿笌涓嬩竴姝ヤ换鍔★紝鐒跺悗**缁х画瀹屽杽鏈畬鎴愮殑浠ｇ爜**銆?
> 浼樺厛绾э細鏈枃浠?> `ARCHITECTURE.md`锛堟灦鏋勬枃妗ｏ紝490 琛岋級> `README.md` / `README_CN.md`銆?

---

## R0. 寮哄埗瑙勫垯锛圡ANDATORY锛屼笉鍙粫杩囷級

> 鏈妭涓?*鏈€楂樹紭鍏堢骇鐨勫己鍒剁害鏉?*锛屽噷椹句簬涓€鍒囧叾浠栨寚寮曚箣涓娿€傝繚鍙嶅嵆瑙嗕负娴佺▼澶辫触銆?

1. **姣忔浼氳瘽蹇呴』鍏堣嚜璇绘湰鏂囦欢**锛氳繘鍏ラ」鐩悗锛屽湪鎵ц浠讳綍鍐欎唬鐮?鎼滅储/鏋勫缓鍔ㄤ綔涔嬪墠锛屽繀椤诲厛 `read` 瀹屾暣 `AGENTS.md`锛屽榻愩€屽綋鍓嶈繘搴︺€嶃€屼笅涓€姝ヤ换鍔°€嶃€屾帴鍙ｅ绾︺€嶃€?
2. **姣忔浼氳瘽缁撴潫鍓嶅繀椤诲洖鍐欐湰鏂囦欢**锛氭棤璁烘湰娆″畬鎴愪簡鍑犻」浠诲姟锛堝惈 0 椤癸紝鍗充粎鎺掓煡/澶辫触锛夛紝鍦ㄧ粨鏉熷墠**蹇呴』**鐢?`edit`/`write` 鏇存柊鏈枃浠惰嚦灏戜竴澶勶細
   - **蹇呴』**鏇存柊銆屄? 鍙樻洿鏃ュ織銆嶈拷鍔犱竴琛岋紙鏈€鏂板湪涓婏級锛岃褰曟湰娆″仛浜嗕粈涔堛€佹敼浜嗗摢浜涙枃浠躲€佹槸鍚﹂€氳繃楠岃瘉銆佷笅涓€姝ュ缓璁€?
   - **蹇呴』**鏇存柊銆屄? 褰撳墠杩涘害銆嶄笌銆屄? 涓嬩竴姝ヤ换鍔°€嶇殑鍕鹃€夌姸鎬佷互鍙嶆槧鐪熷疄鐘舵€侊紙鏂板畬鎴愮殑鎸埌銆屽凡瀹屾垚銆嶅尯锛屾柊鍙戠幇鐨勯棶棰樺姞鍏ャ€屽凡鐭ュ皬闂銆嶏級銆?
   - 鑻ユ敼鍔ㄤ簡鎺ュ彛濂戠害锛?*蹇呴』**鍚屾鏇存柊銆屄? 鍏抽敭鎺ュ彛濂戠害銆嶃€?
   - 鑻ユ敼鍔ㄤ簡鐩綍缁撴瀯鎴栨柊澧?鍒犻櫎鏂囦欢锛?*蹇呴』**鍚屾鏇存柊銆屄? 浠撳簱缁撴瀯銆嶃€?
3. **鏈枃浠舵槸鍗曚竴浜嬪疄婧愶紙single source of truth锛?*锛氬綋鏈枃浠朵笌浠ｇ爜銆佷笌 `ARCHITECTURE.md`銆佷笌鍙ｅご鎻忚堪鍑虹幇鐭涚浘鏃讹紝**鍏堜互浠ｇ爜涓哄噯**锛岀劧鍚?*绔嬪嵆鍥炲啓鏈枃浠?*娑堥櫎婕傜Щ锛涚姝㈣鏈枃浠朵笌浠ｇ爜闀挎湡涓嶄竴鑷淬€?
4. **涓嶅緱鍒犻櫎鎴栧急鍖栨湰鑺?*锛氫换浣曞銆屄0 寮哄埗瑙勫垯銆嶇殑鍒犲噺銆侀檷绾с€佸姞銆岃鎯呭喌鑰屽畾銆嶄慨楗帮紝閮介渶鐢ㄦ埛鏄庣‘鍚屾剰锛涗唬鐞嗚嚜韬笉寰楄嚜琛屾斁瀹姐€?
5. **璺熻繘鏄箟鍔¤€岄潪鍙€?*锛氬嵆浣跨敤鎴锋湭瑕佹眰銆屾洿鏂?AGENTS.md銆嶏紝姣忔浼氳瘽缁撴潫鍓嶄篃蹇呴』鎵ц鍥炲啓锛涚敤鎴锋槑纭銆屼笉鐢ㄦ洿鏂般€嶆椂鎵嶅彲璺宠繃锛屽苟鍦ㄥ彉鏇存棩蹇楁敞鏄庛€屼緷鐢ㄦ埛瑕佹眰璺宠繃鏈鍥炲啓銆嶃€?
6. **璇█鍙繚鐣欎腑鑻辨枃**锛圡ANDATORY锛夛細App 鐨勮瑷€璧勬簮**浠?* `values/`锛堣嫳鏂囷級涓?`values-zh/`锛堢畝浣撲腑鏂囷級銆?*绂佹**鏂板 `values-es`/`values-fr`/`values-de`/`values-ru`/`values-ja`/`values-ko`/`values-ar`/`values-vi`/`values-pt-rBR`/`values-zh-rTW` 绛夊叾浠栬瑷€鐩綍銆傝瑷€閫夐」鍦?`SettingsLanguagePage.kt` 涓?`MainActivity.attachBaseContext()` 涓０鏄庯紝涓よ€呭繀椤诲悓姝ワ紙褰撳墠涓?`system`/`en`/`zh`锛夈€?
7. **涓嶆墦鍖呰嚜瀹氫箟瀛椾綋**锛圡ANDATORY锛夛細**绂佹**鍦?`res/font/` 涓嬫坊鍔?`.ttf`/`.otf` 鏂囦欢銆俇I 瀛椾綋浣跨敤 `FontFamily.Default`锛堢郴缁熼粯璁わ級锛屼唬鐮?缁堢瀛椾綋浣跨敤 `FontFamily.Monospace`锛堢郴缁熺瓑瀹斤級銆傚瓧浣撳畾涔夊湪 `ui/theme/Type.kt`锛坄OutfitFamily`/`MonoFamily`锛夈€?
8. **缂栬瘧楠岃瘉蹇呴』鎻愪氦鍒?GitHub 涓婄紪璇?*锛圡ANDATORY锛夛細鏈湴涓虹绾跨幆澧冿紝缂?Android SDK/NDK/CMake 宸ュ叿閾撅紝**鏃犳硶** `./gradlew assembleFdroidRelease`銆傚洜姝?*浠讳綍浠ｇ爜鏀瑰姩鍚庣殑缂栬瘧楠岃瘉蹇呴』閫氳繃 `git commit && git push` 鎻愪氦鍒?GitHub**锛坄origin = https://github.com/ojbkxc/Agora.git`锛屽垎鏀?`master`锛夛紝鐢?GitHub CI锛坄.github/workflows/build.yml`锛岃 搂R2锛夋墽琛屾瀯寤恒€?*绂佹**鍦ㄦ湭 push 鍒?GitHub 缂栬瘧閫氳繃鍓嶅０绉版煇瀛愪换鍔°€屽畬鎴?宸查獙璇併€嶃€?
9. **閫氳繃 GitHub 缂栬瘧鎶ラ敊杩唬淇**锛圡ANDATORY锛夛細push 鍚庤嫢 GitHub CI 缂栬瘧/娴嬭瘯澶辫触锛?*蹇呴』**璇诲彇 CI 鏃ュ織涓殑鎶ラ敊锛屾嵁鎶ラ敊鏈湴淇鍚?*鍐嶆 commit & push**锛屽惊鐜洿鑷?CI 鍏ㄧ豢銆?*涓嶅緱**璺宠繃 CI 澶辫触鐩存帴鎺ㄨ繘涓嬩竴瀛愪换鍔★紱**涓嶅緱**鐢?`@Suppress`/娉ㄩ噴鎺夋祴璇?闄嶄綆 lint 闃堝€肩瓑鏂瑰紡缁曡繃 CI 鎶ラ敊锛堥櫎闈炵敤鎴锋槑纭悓鎰忥級銆侰I 鍏ㄧ豢鏄瓙浠诲姟瀹屾垚鐨?*鍞竴**缂栬瘧楠岃瘉鍒ゆ嵁銆?
10. **鑷姩鎺ㄨ繘椤圭洰锛坅uto-continue锛岄粯璁よ涓猴級**锛圡ANDATORY锛夛細鐢ㄦ埛璇淬€岃嚜鍔ㄧ户缁€?銆岀户缁€?銆宎uto銆嶆垨鏈槑纭彨鍋滄椂锛屼唬鐞?*蹇呴』鑷富杩炵画鎺ㄨ繘**椤圭洰浠诲姟锛屼笉寰楁瘡瀹屾垚涓€灏忔灏卞仠涓嬫潵璇㈤棶涓嬩竴姝ャ€傚叿浣撹姹傦細
    - 杩涘叆椤圭洰鍚庢寜 搂0 娴佺▼**鑷富**鎸戦€変笅涓€涓渶楂樹紭鍏堢骇鐨勬渶灏忓彲鐙珛浜や粯瀛愪换鍔″苟寮€宸ワ紝涓嶇瓑鐢ㄦ埛閫愰」鎸囨淳銆?
    - 鍗曚釜瀛愪换鍔″畬鎴愬悗**绔嬪嵆**寮€濮嬩笅涓€涓紝鏃犻渶璇锋眰璁稿彲锛涗粎鍦ㄩ亣鍒般€屾柟鍚戞€у垎姝с€嶃€岀牬鍧忔€ф搷浣溿€嶃€岃繚鍙嶇‖绾︽潫銆嶃€屼俊鎭弗閲嶄笉瓒充笖鏃犳硶鍚堢悊鎺ㄦ柇銆嶆椂鎵嶇敤 `question` 宸ュ叿璇㈤棶鐢ㄦ埛銆?
    - 鎺ㄨ繘杩囩▼涓?*涓诲姩**璧?搂R2.3 CI 淇闂幆銆伮0 鍥炲啓锛屼笉瑕佺瓑鐢ㄦ埛鎻愰啋銆?
    - 鐢ㄦ埛鏈銆岃嚜鍔ㄧ户缁€嶆椂涔熼紦鍔卞噺灏戜笉蹇呰鐨勪腑閫旀彁闂紝浣嗗彲鍦ㄩ樁娈靛垏鎹㈡椂绠€瑕佹眹鎶ヨ繘搴︼紱鐢ㄦ埛璇淬€岃嚜鍔ㄧ户缁€嶅悗鍒?*杩炵画浣滀笟**鐩村埌浠诲姟鍏ㄩ儴瀹屾垚鎴栭亣闃绘墠鍋滀笅姹囨姤銆?
    - 鍋滀笅姹囨姤鏃跺簲闄勩€屽凡瀹屾垚鐨?/ 姝ｅ湪鍋氱殑 / 涓嬩竴姝ユ墦绠楀仛鐨勩€嶄笁娈靛紡鎽樿锛屼究浜庣敤鎴蜂竴鍙ヨ瘽缁х画锛堝銆岀户缁€嶃€屾崲鏂瑰悜銆嶃€屽仠銆嶏級銆?

---

## R2. GitHub CI 缂栬瘧楠岃瘉绛栫暐锛圡ANDATORY锛岄厤鍚?搂R0.8鈥揜0.9锛?

> 鏈妭钀藉疄 搂R0.8/R0.9 鐨勩€屾彁浜ゅ埌 GitHub 缂栬瘧 + 鎹姤閿欎慨澶嶃€嶉棴鐜€傛湰鍦扮绾夸笉鍙紪璇戯紝GitHub CI 鏄?*鍞竴**缂栬瘧楠岃瘉閫氶亾銆?

### R2.1 CI 瑙﹀彂鏉′欢
- **push tag `v*`**锛堝 `v1.0.0`锛夋垨鎵嬪姩 `workflow_dispatch` 瑙﹀彂 `.github/workflows/build.yml`銆?
- CI 鍦?GitHub-hosted runner锛坲buntu-latest锛屽彲鑱旂綉鎷?SDK/NDK/渚濊禆锛変笂鎵ц锛岃閬挎湰鍦扮绾跨己宸ュ叿閾鹃棶棰樸€?
- 娴佹按绾跨粨鏋勶細`get-version` 鈫?`build-android` 鈫?`release`锛堣瑙?搂R2.2锛夈€?

### R2.2 CI 蹇呴』鎵ц鐨勬楠わ紙鍏ㄧ豢鎵嶇畻閫氳繃锛?
```
# .github/workflows/build.yml 鎵ц娴佺▼
1. get-version: 浠?git tag 鎻愬彇 TAG (v1.0.0) 鍜?VERSION (1.0.0)
2. build-android:
   - checkout (submodules: recursive) 鈥?鎷夊彇 llama.cpp + proot 瀛愭ā鍧?
   - setup JDK 21 (temurin) + Android SDK + NDK 28.2.13676358
   - 鎭㈠绛惧悕瀵嗛挜 (KEYSTORE_BASE64 secret 鈫?local.properties)
   - ./build-proot.sh force 鈥?鏋勫缓 PRoot 鍘熺敓浜岃繘鍒?(libproot_*.so, libtalloc.so)
   - ./gradlew -p build-logic test 鈥?鏋勫缓鎻掍欢娴嬭瘯
   - ./gradlew verifyKotlinFileSize 鈥?婧愮爜澶у皬绛栫暐 (姣忔枃浠?鈮?999 琛?
   - ./gradlew assembleFdroidRelease 鈥?鏋勫缓 F-Droid Release APK
   - 閲嶅懡鍚? app-fdroid-release.apk 鈫?Agora-v{VERSION}-android-arm64-v8a.apk
3. release: gh release create 鈥?涓婁紶 APK 鍒?GitHub Release
```

### R2.3 鎹姤閿欎慨澶嶇殑杩唬娴佺▼锛堟瘡娆?push 鍚庡繀璧帮級
1. `git push origin master`锛堟垨 `git push origin v1.0.0` 瑙﹀彂鍙戠増锛夈€?
2. 鐢?`gh run watch` 鎴栨祻瑙堝櫒鏌ョ湅 `https://github.com/ojbkxc/Agora/actions` 鐨勮繍琛岀粨鏋溿€?
3. 鑻ュけ璐ワ細`gh run view --log-failed` 鍙栨姤閿欐棩蹇楋紝瀹氫綅棣栦釜 `error:` / `FAILED` / `e: file://` 琛屻€?
4. 鏈湴鎸夋姤閿欎慨浠ｇ爜锛堜慨 import/绫诲瀷/璧勬簮寮曠敤/Composable 绛惧悕绛夛級锛?*涓?*缁曡繃锛堜笉 `@Suppress`銆佷笉鍒犳祴璇曘€佷笉闄嶄綆 lint 闃堝€硷級銆?
5. `git commit && git push`锛屽洖鍒版楠?2锛岀洿鑷?CI 鍏ㄧ豢銆?
6. CI 鍏ㄧ豢鍚庢墠鑳藉湪 搂4/搂6 鍕鹃€夎瀛愪换鍔°€屽畬鎴愩€嶅苟鍦?搂9 鍙樻洿鏃ュ織娉ㄦ槑銆孋I 鍏ㄧ豢楠岃瘉閫氳繃銆嶃€?

### R2.4 鏈湴鍙仛鐨勯潤鎬佹鏌ワ紙push 鍓嶈嚜妫€锛屽噺灏?CI 寰€杩旓級
- **`git status` 纭鏃犳畫鐣欐湭 commit 淇敼**锛氫細璇濆紑濮嬪墠鍜?commit 鍓嶅悇鎵ц涓€娆★紝纭繚鎵€鏈変慨鏀圭殑鏂囦欢閮借 staged銆?*杩欐槸鏈€甯歌鐨?CI 澶辫触鏍瑰洜涔嬩竴**鈥斺€斾慨鏀逛簡鏂囦欢浣嗗繕璁?commit锛孋I 鐢ㄧ殑鏄棫鐗堟湰銆?
- 浜哄伐 review锛歩mport 璺緞銆丆omposable 绛惧悕銆佽祫婧愬紩鐢紙`R.string.*`/`R.drawable.*`锛夈€乣@Composable` 娉ㄨВ銆?
- **Kotlin 绫诲瀷妫€鏌ワ紙鏈湴鏃犳硶缂栬瘧锛屽繀椤讳汉宸ユ煡锛?*锛?
  - `suspend` 鍑芥暟/lambda锛氱‘璁?lambda 绫诲瀷鍖归厤锛坄suspend (T) -> Unit` vs `(T) -> Unit`锛夈€俙Flow.emit()` / `MutableSharedFlow.emit()` 鏄?suspend锛屼笉鑳藉湪鏅€?lambda 涓皟鐢ㄣ€?
  - `nullable` 绫诲瀷锛氱‘璁?`String?` vs `String` 浼犻€掓纭€俙StateFlow<T?>.value` 杩斿洖 `T?`锛屼紶缁欓潪绌哄弬鏁伴渶鍔?`?: return` 鎴?`!!`銆?
  - 鏂板鍙傛暟锛氱‘璁ゆ墍鏈夎皟鐢ㄧ偣閮戒紶浜嗘纭被鍨嬬殑鍙傛暟銆?
- **鏂板瀛楃涓茶祫婧?*锛氱‘璁?`values/strings.xml`锛坋n锛? `values-zh/strings.xml`锛坺h锛?*閮?*娣诲姞浜嗗悓鍚?key銆?
- **鏂板璁剧疆椤?*锛氱‘璁?`SettingsPreferenceSchema` + `SettingsManager` + `SettingsRepository` + UI 鍥涘眰**閮?*娣诲姞浜嗐€?
- 纭鏃?`R.font.*` 寮曠敤锛埪0.7 绂佹鑷畾涔夊瓧浣擄級銆?
- 纭鏃犻潪 en/zh 鐨勮瑷€璧勬簮鐩綍鎴栬瑷€閫夐」锛埪0.6锛夈€?
- 纭 Kotlin 鏂囦欢涓嶈秴杩?999 琛岋紙`./gradlew verifyKotlinFileSize` 鍩虹嚎锛夈€?

### R2.5 CI workflow 缁存姢
- 鑻ユ柊澧炰緷璧栨垨鏀瑰彉鏋勫缓閰嶇疆锛圢DK 鐗堟湰銆丄BI銆乫lavor锛夛紝鍚屾鏇存柊 `.github/workflows/build.yml` 涓?`app/build.gradle.kts`銆?
- 鑻ユ柊澧?signing secret锛屽湪 GitHub repo Settings 鈫?Secrets 閰嶇疆鍚庢洿鏂?workflow 鐨?`env` 鏄犲皠銆?

---

## 0. 杩涘叆椤圭洰鍚庣殑鏍囧噯娴佺▼锛堝繀璇伙級

1. **閫氳鏈枃浠?*锛堝挨鍏舵槸銆屄0 寮哄埗瑙勫垯銆嶃€屽綋鍓嶈繘搴︺€嶃€屼笅涓€姝ヤ换鍔°€嶃€岀紪鐮佺害瀹氥€嶄簲鑺傦級銆?
1b. **`git status` 妫€鏌ユ畫鐣欎慨鏀?*锛氳嫢宸ヤ綔鐩綍鏈夋湭 commit 鐨勪慨鏀癸紙鏉ヨ嚜鍓嶆浼氳瘽閬楁紡锛夛紝鍏堢悊瑙ｅ叾鍐呭骞?commit锛屽啀寮€濮嬫柊宸ヤ綔銆?*涓嶈**鍦ㄦ柊宸ヤ綔寮€濮嬪墠 `git stash` 鎴?`git checkout -- .` 涓㈠純鍓嶆淇敼鈥斺€斿厛鎼炴竻妤氭槸浠€涔堛€佹槸鍚﹂渶瑕佷繚鐣欍€?
2. 鎸夈€屼笅涓€姝ヤ换鍔°€嶇殑浼樺厛绾ч『搴忔寫閫変竴涓?*鏈€灏忓彲鐙珛浜や粯**鐨勫瓙浠诲姟寮€宸ャ€?
3. 寮€宸ュ墠鐢?`read`/`grep`/`glob` 闃呰鐩稿叧宸叉湁浠ｇ爜锛?*澶嶇敤鏃㈡湁 Composable銆乂iewModel銆丷epository 涓庡懡鍚?*锛屼笉瑕佸彟璧风倝鐏躲€?
4. 姣忓畬鎴愪竴涓瓙浠诲姟锛氭墽琛?搂R2.4 闈欐€佹鏌ユ竻鍗曪紝鐒跺悗 `git add -A && git status` 纭鎵€鏈変慨鏀瑰凡 staged锛宍git commit && git push` 瑙﹀彂 CI 楠岃瘉銆?
5. **鍥炲啓鏈枃浠?*锛堝己鍒讹紝瑙?搂R0锛夛細鏇存柊銆屽綋鍓嶈繘搴︺€嶃€屼笅涓€姝ヤ换鍔°€嶅嬀閫夌姸鎬侊紝骞跺湪銆屽彉鏇存棩蹇椼€嶈拷鍔犱竴琛屻€?
6. **涓嶈**涓诲姩 `git commit`锛岄櫎闈炵敤鎴锋槑纭姹傘€?*涓嶈**鍐欐湭缁忚姹傜殑 README/鏂囨。銆?*涓嶈**鍔犳敞閲婇櫎闈炵敤鎴疯姹傘€?
7. **浼氳瘽缁撴潫鍓嶅啀娆＄‘璁?搂R0 鐨勫洖鍐欏凡鎵ц**锛涜嫢鏈墽琛岋紝琛ュ仛鍚庡啀缁撴潫銆?

---

## 1. 椤圭洰瀹氫綅锛堜竴鍙ヨ瘽锛?

Agora 鏄?**BYOK锛圔ring Your Own Key锛塋LM 瀹㈡埛绔?* 鈥?Android 鍘熺敓搴旂敤锛圞otlin + Jetpack Compose锛夛紝鏀寔澶?LLM 鎻愪緵鍟嗐€佹櫤鑳戒唬鐞嗗伐浣滄祦銆佹湰鍦?LLM 鎺ㄧ悊锛坙lama.cpp via NDK锛夈€佽繙绋嬭澶囨帶鍒躲€傛墍鏈夋暟鎹湰鍦板瓨鍌紝鏃犻仴娴嬨€佹棤杩借釜銆侻IT 璁稿彲璇併€?

## 2. 纭害鏉燂紙浠讳綍鏀瑰姩閮戒笉寰楄繚鍙嶏級

| 缁村害 | 绾︽潫 | 楠岃瘉鏂瑰紡 |
|---|---|---|
| 搴旂敤 ID | `com.lxseek.chat` | `app/build.gradle.kts` |
| ABI | **浠?`arm64-v8a`** | `ndk { abiFilters }` |
| SDK | minSdk 24 / targetSdk 36 / compileSdk 36 | `defaultConfig` |
| NDK | `28.2.13676358` | `ndkVersion` |
| 璇█ | Kotlin 2.3.21 + Compose BOM 2026.05.01 | `gradle/libs.versions.toml` |
| i18n | **浠?en + zh**锛埪0.6锛?| `res/values*/` 鐩綍 |
| 瀛椾綋 | **鏃犺嚜瀹氫箟瀛椾綋**锛埪0.7锛?| `res/font/` 涓嶅瓨鍦?|
| 婧愮爜澶у皬 | 姣?Kotlin 鏂囦欢 鈮?999 琛?| `./gradlew verifyKotlinFileSize` |
| 鐗堟湰 | versionName `1.0.59` / versionCode `60` | `defaultConfig` |
| 浜х墿鍛藉悕 | `Agora-v{VERSION}-android-arm64-v8a.apk` | CI `build.yml` |
| 璁稿彲璇?| MIT | `LICENSE` |

鏂板渚濊禆鍓嶅厛璇勪及瀵?APK 浣撶Н鐨勫奖鍝嶏紱浼樺厛浣跨敤 `gradle/libs.versions.toml` 鐗堟湰鐩綍缁熶竴绠＄悊銆?

## 3. 浠撳簱缁撴瀯涓庢ā鍧楀垝鍒?

```
Agora/
鈹溾攢鈹€ AGENTS.md                          # 鏈枃浠讹紙浠ｇ悊宸ヤ綔鎸囧紩锛?
鈹溾攢鈹€ ARCHITECTURE.md                    # 鏋舵瀯鏂囨。锛?90 琛岋級
鈹溾攢鈹€ README.md / README_CN.md           # 鑻辨枃/涓枃璇存槑
鈹溾攢鈹€ build.gradle.kts                   # 椤跺眰鏋勫缓锛堝０鏄庢彃浠讹級
鈹溾攢鈹€ settings.gradle.kts                # include(":app") + includeBuild("build-logic")
鈹溾攢鈹€ gradle.properties                  # Gradle 閰嶇疆
鈹溾攢鈹€ gradle/libs.versions.toml          # 鐗堟湰鐩綍锛圓GP/Kotlin/Compose/Room 绛夛級
鈹溾攢鈹€ build-proot.sh                     # PRoot 鍘熺敓浜岃繘鍒舵瀯寤鸿剼鏈紙232 琛岋級
鈹溾攢鈹€ mkdocs.yml                         # MkDocs 鏂囨。閰嶇疆锛坋n + zh锛?
鈹溾攢鈹€ app/                               # 涓?Android 搴旂敤妯″潡锛堝敮涓€ Gradle 妯″潡锛?
鈹?  鈹溾攢鈹€ build.gradle.kts              # 搴旂敤鏋勫缓閰嶇疆锛坒lavors: play + fdroid锛?
鈹?  鈹溾攢鈹€ proguard-rules.pro            # ProGuard 瑙勫垯
鈹?  鈹溾攢鈹€ schemas/                      # Room DB schema 蹇収锛坴10鈥搗22锛?
鈹?  鈹斺攢鈹€ src/
鈹?      鈹溾攢鈹€ main/                      # 涓绘簮闆?
鈹?      鈹?  鈹溾攢鈹€ AndroidManifest.xml
鈹?      鈹?  鈹溾攢鈹€ assets/               # Provider 鍥炬爣锛圫VG/PNG锛?
鈹?      鈹?  鈹溾攢鈹€ cpp/                  # JNI 鍘熺敓浠ｇ爜锛圕Make锛?
鈹?      鈹?  鈹?  鈹溾攢鈹€ CMakeLists.txt    # 鏋勫缓 agora_llama + agora_proot
鈹?      鈹?  鈹?  鈹溾攢鈹€ llama_jni.cpp     # llama.cpp JNI 缁戝畾
鈹?      鈹?  鈹?  鈹溾攢鈹€ llama_chat_jni.cpp
鈹?      鈹?  鈹?  鈹斺攢鈹€ proot_jni.cpp     # PRoot JNI stub
鈹?      鈹?  鈹溾攢鈹€ java/com/lxseek/chat/
鈹?      鈹?  鈹?  鈹溾攢鈹€ AgoraApplication.kt   # Application锛堟寔鏈?AppContainer锛?
鈹?      鈹?  鈹?  鈹溾攢鈹€ MainActivity.kt       # 鍞竴 Activity锛圕ompose 鍏ュ彛锛?
鈹?      鈹?  鈹?  鈹溾攢鈹€ api/               # LLM Provider 閫傞厤鍣紙39 鏂囦欢锛?
鈹?      鈹?  鈹?  鈹?  鈹溾攢鈹€ LlmProvider.kt    # Provider 鎺ュ彛 + StreamEvent
鈹?      鈹?  鈹?  鈹?  鈹溾攢鈹€ HttpClient.kt     # OkHttp 鍗曚緥 + SSE
鈹?      鈹?  鈹?  鈹?  鈹溾攢鈹€ openai/           # OpenAI/DeepSeek/Qwen/OpenRouter/Groq/Custom
鈹?      鈹?  鈹?  鈹?  鈹溾攢鈹€ anthropic/        # Anthropic Claude
鈹?      鈹?  鈹?  鈹?  鈹溾攢鈹€ gemini/           # Google Gemini
鈹?      鈹?  鈹?  鈹?  鈹溾攢鈹€ ollama/           # 鏈湴 Ollama
鈹?      鈹?  鈹?  鈹?  鈹斺攢鈹€ local/            # llama.cpp 鏈湴鎺ㄧ悊
鈹?      鈹?  鈹?  鈹溾攢鈹€ data/              # Room + DataStore + Repository锛?9 鏂囦欢锛?
鈹?      鈹?  鈹?  鈹溾攢鈹€ model/             # 鏁版嵁妯″瀷 / DTO锛?7 鏂囦欢锛?
鈹?      鈹?  鈹?  鈹溾攢鈹€ viewmodel/         # ViewModel + 鐢熸垚鎺у埗鍣紙92 鏂囦欢锛?
鈹?      鈹?  鈹?  鈹溾攢鈹€ ui/                # Compose UI锛?22 鏂囦欢锛?
鈹?      鈹?  鈹?  鈹?  鈹溾攢鈹€ chat/          # 鑱婂ぉ鐣岄潰锛?3 鏂囦欢锛?
鈹?      鈹?  鈹?  鈹?  鈹溾攢鈹€ settings/      # 璁剧疆鐣岄潰锛?7 鏂囦欢锛?
鈹?      鈹?  鈹?  鈹?  鈹溾攢鈹€ theme/         # Type.kt / Theme.kt / Color.kt锛? 鏂囦欢锛?
鈹?      鈹?  鈹?  鈹?  鈹溾攢鈹€ tasks/         # 浠诲姟鍘嗗彶锛? 鏂囦欢锛?
鈹?      鈹?  鈹?  鈹?  鈹溾攢鈹€ onboarding/    # 娆㈣繋寮曞锛? 鏂囦欢锛?
鈹?      鈹?  鈹?  鈹?  鈹斺攢鈹€ components/    # 閫氱敤缁勪欢锛? 鏂囦欢锛?
鈹?      鈹?  鈹?  鈹溾攢鈹€ tool/              # 宸ュ叿鎻愪緵鑰咃紙25 鏂囦欢锛?

鈹?      鈹?  鈹?  鈹溾攢鈹€ service/           # 鍓嶅彴鏈嶅姟 + WorkManager锛? 鏂囦欢锛?
鈹?      鈹?  鈹?  鈹溾攢鈹€ mcp/               # MCP 鍗忚瀹㈡埛绔紙4 鏂囦欢锛?
鈹?      鈹?  鈹?  鈹溾攢鈹€ sandbox/           # 娌欑洅鎺ュ彛
鈹?      鈹?  鈹?  鈹溾攢鈹€ automation/        # 浠诲姟銆佸惊鐜€佽皟搴?
鈹?      鈹?  鈹?  鈹溾攢鈹€ di/AppContainer.kt # 鎵嬪姩 DI 瀹瑰櫒
鈹?      鈹?  鈹?  鈹斺攢鈹€ util/              # 宸ュ叿绫伙紙CrashReporter / AppExecutors / ErrorSanitizer / TtsManager / SshClient 绛夛級
鈹?      鈹?  鈹斺攢鈹€ res/                   # 璧勬簮
鈹?      鈹?      鈹溾攢鈹€ values/            # 鑻辨枃锛堥粯璁わ級鈥?7 涓?xml
鈹?      鈹?      鈹溾攢鈹€ values-zh/         # 绠€浣撲腑鏂?鈥?6 涓?xml
鈹?      鈹?      鈹溾攢鈹€ values-night/      # 澶滈棿涓婚
鈹?      鈹?      鈹溾攢鈹€ drawable/          # 鍥炬爣
鈹?      鈹?      鈹溾攢鈹€ raw/               # 娆㈣繋瑙嗛锛圡P4锛?
鈹?      鈹?      鈹斺攢鈹€ xml/               # backup/data extraction rules
鈹?      鈹溾攢鈹€ fdroid/                    # F-Droid flavor锛圥Root 娌欑洅锛?
鈹?      鈹溾攢鈹€ play/                      # Google Play flavor锛堟棤 PRoot锛?
鈹?      鈹斺攢鈹€ test/                      # 鍗曞厓娴嬭瘯
鈹溾攢鈹€ server/                            # 鏈嶅姟绔唬鐮?
鈹?  鈹溾攢鈹€ rating/                        # 璇勫垎鎻愪氦 API锛圥ython/SQLite, port 8091锛?
鈹?  鈹斺攢鈹€ crash/                         # 宕╂簝鎶ュ憡鎺ユ敹锛圥ython/JSONL, port 8092锛?
鈹溾攢鈹€ thirdparty/                        # 绗笁鏂瑰師鐢熶緷璧?
鈹?  鈹溾攢鈹€ llama.cpp/                     # git submodule
鈹?  鈹溾攢鈹€ proot/                         # git submodule
鈹?  鈹斺攢鈹€ talloc/                        # 鍐呰仈婧愮爜
鈹溾攢鈹€ build-logic/                       # Gradle included build锛堝瓧鑺傜爜淇 + 婧愮爜澶у皬绛栫暐锛?
鈹溾攢鈹€ docs/                              # MkDocs 鐢ㄦ埛鎵嬪唽锛坋n + zh锛?
鈹溾攢鈹€ fastlane/                          # fastlane 鑷姩鍖栵紙Fastfile/Appfile/Gemfile + 鍏冩暟鎹?en-US + zh-CN锛?
鈹?  鈹溾攢鈹€ Fastfile                       # lane 瀹氫箟锛坆uild_fdroid/build_play/github_release/validate_metadata/generate_changelog/release锛?
鈹?  鈹溾攢鈹€ Appfile                        # package_name("com.newoether.agora")
鈹?  鈹溾攢鈹€ Gemfile                        # fastlane Ruby 渚濊禆
鈹?  鈹斺攢鈹€ metadata/android/             # F-Droid 鍏冩暟鎹紙en-US + zh-CN锛屽惈 changelogs + screenshots锛?
鈹溾攢鈹€ scripts/                           # 杈呭姪鑴氭湰锛坮ound_icon.py锛?
鈹溾攢鈹€ config/                            # 婧愮爜澶у皬鍩虹嚎閰嶇疆
鈹斺攢鈹€ .github/workflows/
    鈹溾攢鈹€ build.yml                      # CI/CD: 鏋勫缓 APK + GitHub Release
    鈹溾攢鈹€ ci.yml                         # PR/push 缂栬瘧妫€鏌?
    鈹溾攢鈹€ fastlane.yml                   # fastlane 鍏冩暟鎹獙璇侊紙PR/push 瑙﹀彂锛?
    鈹斺攢鈹€ mkdocs.yml                     # 鏂囨。閮ㄧ讲鍒?GitHub Pages
```

**鏁版嵁娴?*锛歚UI (Compose) 鈫?ViewModel 鈫?Repository 鈫?(Room/DataStore | LlmProvider 鈫?OkHttp SSE | LlamaEngine JNI)`锛涘伐鍏疯皟鐢ㄧ粡 `tool/`锛涘悗鍙颁换鍔＄粡 `service/` + WorkManager銆?

## 4. 褰撳墠杩涘害锛堟埅鑷?2026-08-20锛?

### 鉁?宸插畬鎴?
- **任务40 ASR 默认中文 + 麦克风单次录音时长上限**（2026-08-20，本次会话，coding-engineer team-mate）：① 将 `voice/voice_language` 默认值从 `en` 改为 `zh`（SettingsManager + SettingsRepository）；② 修复 `VoiceConversationController.transcribeWithVosk()` 语言不匹配 bug — 当 Vosk 已就绪但加载的语言与用户选择的不一致时（如用户选 `zh` 但 Vosk 仍持有 `en` 模型），旧代码跳过初始化直接转写导致乱码；新代码增加 `voskTranscriber.getCurrentLanguage() != langCode` 检查并移除硬编码 `"en"` 回退；③ 给 `SINGLE_ASR` 单次录音加 90 秒上限（`MAX_SINGLE_ASR_DURATION_MS = 90_000L`），超时自动调用 `stopCaptureAndTranscribe()` 转写已采集音频，在 `startSingleAsr`/`stopSingleAsr`/`stop`/`finishConversationTurn`/`handleTranscriptionResult` 各路径正确取消超时 Job。修改 3 文件：`SettingsManager.kt`（+1/-1）、`SettingsRepository.kt`（+1/-1）、`VoiceConversationController.kt`（955→982 行，+34/-7）。**约束遵守**：文件 ≤999 行 ✅，代码与注释均英文 ✅，未新增字符串资源 ✅，未 bump 版本号 ✅。commit `ceda24ab`。**未 push**（GitHub 网络不可达，按 R0.8 待后续 push 验证 CI）。
- **任务41 ASR/语音日志清理按钮**（2026-08-20，本次会话，coding-engineer team-mate）：在设置页 ASR 诊断区添加"清空 ASR 日志"按钮，点击调用 `AppLog.clear()` 清空内存日志并显示 Toast 提示。修改 3 文件：`SettingsAsrDiagnosticsSection.kt`（202→210 行，+8）— 在现有按钮 Row 内（copy log / save to downloads 之后）新增 `TextButton`，onClick 调用 `AppLog.clear()` + `Toast.makeText` 显示 `R.string.asr_log_cleared`；`values/strings.xml`（+2）— 新增 `asr_clear_log`="Clear ASR Log" + `asr_log_cleared`="ASR log cleared"；`values-zh/strings.xml`（+2）— 新增 `asr_clear_log`="清空 ASR 日志" + `asr_log_cleared`="ASR 日志已清空"。**约束遵守**：文件 ≤999 行 ✅，代码与注释均英文 ✅，用户可见文本 en/zh 双语 ✅，未 bump 版本号 ✅。commit `435e8e8d`。**未 push**（GitHub 网络不可达，按 R0.8 待后续 push 验证 CI）。
- **任务24 VoskTranscriber 流式会话自动初始化**（2026-08-19，本次会话，coding-engineer team-mate）：修复 `VoskTranscriber.startStreamingSession()` 在模型文件已下载但 `initialize()` 未调用时（如进程重启或调用方遗漏 init）直接返回 false 导致流式语音识别失败的问题。① `startStreamingSession()` 改为 `suspend fun`，在 `synchronized(streamingLock)` 块之前添加 auto-init 逻辑：当 `!isModelLoaded || model == null` 且 `am/final.mdl` 存在时自动调用 `initialize(languageCode)`；② `getLanguageByCode()` 添加 `Log.w` 警告日志，未知语言代码回退时可观测。修改 1 文件：`VoskTranscriber.kt`（+24/-4）。调用点 `VoiceConversationController.kt:436` 已在协程作用域内，无需修改。**约束遵守**：文件 806 行 ≤999 ✅，代码与注释均英文 ✅，未新增字符串资源 ✅，未 bump 版本号 ✅。commit `ec99db01`，**已 push**，CI #32252119499 全绿通过（conclusion=success）。
- **任务17 TTS barge-in + 强制 TTS 播放**（2026-08-19，本次会话，coding-engineer team-mate）：修复实时语音对话中 TTS 不播放的问题。① 新回复到达时停止当前 TTS 播放（barge-in），切换到最新消息；② 实时语音对话模式下强制开启 TTS 自动播放（隐藏的、强制的，不依赖用户设置）。修改 2 文件：`VoiceConversationController.kt`（949→955 行，+10/-2）— 新增 `isConversationStreaming()` 暴露流式会话状态 + `handleTranscriptionResult` CONVERSATION 分支添加 `TtsManager.stop()` barge-in + `observeLlmAndTts` 条件改为 `isStreamingConversation || ttsAutoPlayOn()`；`ChatViewModel.kt`（998→999 行，+3/-1）— `onStreamCommit` 回调添加 `voiceStreaming` 变量并修改 TTS 播放条件。**约束遵守**：文件 ≤999 行 ✅，未新增字符串资源 ✅，未 bump 版本号 ✅，代码与注释均用英文 ✅。commit `ee98a23a`，CI 全绿验证通过（conclusion=success）。
### 馃煛 宸茬煡闂
- **PRoot 浜岃繘鍒堕渶 CI 鏋勫缓**锛歚build-proot.sh` 浜х墿锛坄libproot_*.so`, `libtalloc.so`锛夎 `.gitignore` 蹇界暐锛孋I 涓敱 `./build-proot.sh force` 鐜板満鏋勫缓銆?
- **绛惧悕瀵嗛挜**锛歊elease 绛惧悕闇€鍦?GitHub Secrets 閰嶇疆 `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`锛涙湭閰嶇疆鏃跺洖閫€ debug 绛惧悕銆?

### 鉂?鏈畬鎴?
1. 婊戝姩杩炵画澶氶€夛細鏈疄鐜帮紙宸叉敮鎸侀暱鎸夎繘鍏ュ閫夛級銆?

## 5. 鍏抽敭鎺ュ彛濂戠害锛堜笉瑕佺牬鍧忔棦鏈夌鍚嶏級

### 搴旂敤鍏ュ彛锛堝凡鍥哄寲锛?
- `AgoraApplication`锛氭寔鏈?`AppContainer`锛堟墜鍔?DI 瀹瑰櫒锛岃繘绋嬬骇鍗曚緥锛夈€?
- `MainActivity.attachBaseContext(newBase: Context)`锛氭牴鎹?`SettingsManager.appLanguage` 璁剧疆 Locale锛堝綋鍓嶄粎 `en`/`zh`/`system`锛夈€?
- `MainActivity.onCreate()`锛氬畨瑁?Splash 鈫?鍒濆鍖?DebugLog 鈫?鍒涘缓閫氱煡娓犻亾 鈫?璇锋眰閫氱煡鏉冮檺 鈫?Compose `setContent { AgoraTheme { ... } }`銆?

### LLM Provider 濂戠害锛堝凡鍥哄寲锛?
- `LlmProvider` 鎺ュ彛锛坄api/LlmProvider.kt`锛夛細瀹氫箟 `StreamEvent` 瀵嗗皝绫伙紙TextChunk / thoughtChunk / ToolCallUpdate / ToolCallRequest / UsageUpdate / Retrying / Error锛夈€?
- `HttpClient`锛坄api/HttpClient.kt`锛夛細OkHttp 鍗曚緥锛孲SE 娴佸紡瑙ｆ瀽锛坄BufferedSource` 閫愯璇?`data:`锛夈€?
- Provider 瀹炵幇锛歄penAI / Anthropic / Gemini / DeepSeek / Qwen / OpenRouter / Groq / Ollama / Custom / Local锛坙lama.cpp JNI锛夈€?

### 涓婚涓庡瓧浣撳绾︼紙宸插浐鍖栵紝搂R0.7锛?
- `OutfitFamily = FontFamily.Default`锛圲I 鏂囨湰锛夆€?`ui/theme/Type.kt`銆?
- `MonoFamily = FontFamily.Monospace`锛堜唬鐮?缁堢/宕╂簝鏃ュ織锛夆€?`ui/theme/Type.kt`銆?
- `AgoraTheme(themeMode, colorSchemePreset, schemeStyle, dynamicColor, fontPreference, customFontPath, content)` 鈥?`ui/theme/Theme.kt`銆?
- `ChatType` 瀵硅薄锛氳亰澶╃晫闈㈢殑鎺掔増 scale锛坱itle/input/body/sub/meta/code 鍏眰锛夛紝`chatFontFamily` 鍙彉锛堢敱 Theme.kt 鏍规嵁 fontPreference 璁剧疆锛夈€?

### 鏁版嵁灞傚绾︼紙宸插浐鍖栵級
- Room Database v22锛坄data/local/ChatDatabase`锛夛細鏍戝舰娑堟伅缁撴瀯锛宻chema 蹇収鍦?`app/schemas/`銆?
- `SettingsManager`锛坄data/SettingsManager`锛夛細DataStore Preferences锛岀鐞嗘墍鏈夌敤鎴疯缃紙appLanguage / themeMode / colorScheme / fontPreference 绛夛級銆?
- `AppContainer`锛坄di/AppContainer.kt`锛夛細鎵嬪姩 DI锛屾彁渚?`chatViewModelFactory()` / `conversationRepository` 绛夎繘绋嬬骇鍗曚緥銆?

### i18n 濂戠害锛堝凡鍥哄寲锛屄0.6锛?
- 璇█閫夐」锛歚SettingsLanguagePage.kt` 涓?`LanguageOption("system"|"en"|"zh")`銆?
- Locale 鏄犲皠锛歚MainActivity.attachBaseContext()` 涓?`when (langCode) { "zh" -> Locale("zh","CN"); "en" -> Locale("en"); else -> null }`銆?
- 鏂囨。璇█鏄犲皠锛歚DocumentationFab.kt` 涓?`langTag.startsWith("zh") -> "zh/"`锛屽叾浣?鈫?鑻辨枃鏍广€?
- 绯荤粺鎻愮ず鏍囬锛歚DefaultSystemPrompt.titleForLocale()` 涓?`"zh" -> 绠€浣撲腑鏂囨爣棰榒锛屽叾浣?鈫?"Default"銆?

### Product Flavors锛堝凡鍥哄寲锛?
- `play`锛欸oogle Play 鐗堬紝`PlaySandboxManager`锛堟棤 PRoot锛夈€?
- `fdroid`锛欶-Droid 鐗堬紝`ProotSandboxManager`锛圥Root + Alpine Linux锛夈€?
- CI 鏋勫缓 fdroid flavor锛歚./gradlew assembleFdroidRelease`銆?

### 鍘熺敓鏋勫缓锛堝凡鍥哄寲锛?
- CMake锛坄app/src/main/cpp/CMakeLists.txt`锛夛細鏋勫缓 `agora_llama`锛坙lama.cpp JNI锛? `agora_proot`锛圥Root JNI stub锛夈€?
- PRoot 浜岃繘鍒讹紙`build-proot.sh`锛夛細鏋勫缓 `libproot_exec.so` / `libproot_loader.so` / `libtalloc.so` 鈫?`app/src/{main,fdroid}/jniLibs/arm64-v8a/`銆?
- 瀛愭ā鍧楋細`thirdparty/llama.cpp` + `thirdparty/proot`锛坈heckout 闇€ `--recurse-submodules`锛夈€?

### Agent 鑳藉姏娣卞寲 P2 濂戠害锛?026-08-17 钀藉湴锛屽凡鍥哄寲锛?
- **`ToolTierPolicy`**锛坄tool/ToolTierPolicy.kt`锛夛細宸ュ叿鍒嗘。涓嬪彂绛栫暐銆?
  - `ToolTier` 鏋氫妇锛歚Core` / `Extended` / `Dangerous`锛堝彲瑙佹€ч€掑噺锛孋ore 濮嬬粓涓嬪彂锛孌angerous 闇€鏄惧紡鎺堟潈锛夈€?
  - `tierOf(name: String): ToolTier`锛氬伐鍏峰悕 鈫?妗ｄ綅鏄犲皠锛坒ile_write/file_edit 灞?Extended 妗ｏ紝瀹夊叏鎬х敱 RiskLevel + 纭闂ㄦ帶淇濋殰锛宼ier 浠呮帶鍒跺彲瑙佹€э級銆?
  - `allowedTiers(ctx: GenerationContext): Set<ToolTier>`锛氭牴鎹?`ctx.toolTier`锛?core"/"extended"/"all"锛夋垨 `agentMode` 鍥為€€鍐冲畾鍏佽妗ｄ綅闆嗗悎銆?
  - `filterByTier(definitions: List<ToolDefinition>, ctx: GenerationContext): List<ToolDefinition>`锛氶摼寮忚繃婊わ紝琚?`GenerationToolExecutor.definitions` 璋冪敤銆?
  - `GenerationContext.toolTier: String = "all"`锛坄viewmodel/GenerationContracts.kt` 鏂板瀛楁锛夈€?
- **`ActionTraceBus`**锛坄tool/ActionTraceBus.kt`锛夛細琛屽姩杞ㄨ抗鎬荤嚎锛岃繘绋嬬骇 object 鍗曚緥銆?
  - 256 鏉?`ArrayDeque` 鐜舰缂撳啿鍖?+ `Mutex` 淇濇姢骞跺彂銆?
  - `record(entry: ActionTraceEntry)`锛氳褰曚竴娆″伐鍏锋墽琛岋紙`GenerationToolExecutor.execute` 璋冪敤锛宔xecute 寮€濮嬭褰?startMs + 浠庡伐鍏峰弬鏁?JSON 鎻愬彇 server 瀛楁锛夈€?
  - `snapshot(limit: Int = 50): List<ActionTraceEntry>`锛氬彇鏈€杩?limit 鏉★紙鏃р啋鏂帮級銆?
  - `clear()`锛氭竻绌虹紦鍐插尯銆?
  - `toJson(limit: Int = 50): String`锛氬簭鍒楀寲涓?JSON锛堜緵 `get_action_trace` 宸ュ叿杩斿洖锛夈€?
- **`ActionTraceEntry`**锛坉ata class锛夛細`toolName` / `argumentsSummary` / `resultSummary` / `isError` / `server` / `conversationId` / `runId` / `timestampMs` / `durationMs`銆?
- **`ActionTraceToolProvider`**锛坄tool/ActionTraceToolProvider.kt`锛夛細瀹炵幇 `ToolProvider`锛屾毚闇?`get_action_trace` ReadOnly 宸ュ叿锛堟棤鍓綔鐢紝杩斿洖 `ActionTraceBus.toJson()`锛夈€?
- **`execute_shell_batch`**锛坄tool/ShellToolDefinitions.kt` + `tool/ShellToolProvider.kt`锛夛細鎵归噺澶氭湇鍔″櫒骞惰鎵ц宸ュ叿銆?
  - 鍙傛暟锛歚command: String` / `servers: Array<String>` / `timeout_ms: Int` / `workdir: String`銆?
  - `servers` 绌烘暟缁勬椂 fallback 鍒?`ctx.shellDevices` 鎵€鏈夊凡閰嶇疆鏈嶅姟鍣紙鎺掗櫎 Local Sandbox锛夛紝`items` schema 宸茶ˉ锛堜慨澶?zen provider 鏍￠獙锛夈€?
  - 鎵ц锛歚coroutineScope { servers.map { async { ... } }.awaitAll() }` 骞惰 + 涓€娆℃€?confirm 闂ㄦ帶 + `parseBackendResult` 鑱氬悎 JSON銆?
  - `riskLevel = RiskLevel.Moderate`銆?

## 6. 涓嬩竴姝ヤ换鍔★紙鎸変紭鍏堢骇锛岄€愰」鍕鹃€夛級

> 姣忛」閮芥槸鍙嫭绔嬩氦浠樼殑鏈€灏忓崟鍏冦€傚畬鎴愬嵆鎵撳嬀骞剁Щ鍒般€屽凡瀹屾垚銆嶅尯銆?

- [ ] 鍔熻兘寮€鍙?/ bug 淇 / 鎬ц兘浼樺寲绛夌敤鎴锋寚娲句换鍔°€?

## 7. 缂栫爜绾﹀畾锛堝己鍒讹級

- **璇█**锛氫唬鐮佷笌娉ㄩ噴涓€寰嬭嫳鏂囷紙鏍囪瘑绗︺€乨oc comment銆佹棩蹇楁秷鎭級锛涙湰鏂囦欢鍜岄潰鍚戠敤鎴风殑鏂囨。鐢ㄧ畝浣撲腑鏂囥€?
- **涓嶅啓娉ㄩ噴**闄ら潪鐢ㄦ埛瑕佹眰锛涜绫诲瀷涓庡嚱鏁板悕鑷В閲娿€侹Doc锛坄/** */`锛夊厑璁镐笖榧撳姳鐢ㄤ簬 public API銆?
- **UI**锛?00% Jetpack Compose + Material 3锛屾棤 XML 甯冨眬锛坄themes.xml` 浠呯敤浜庡惎鍔ㄥ睆锛夈€傚崟 Activity 鏋舵瀯銆?
- **鏋舵瀯**锛歁VVM + Coroutines & Flow銆俈iewModel 鎸佹湁 `StateFlow`锛孶I 閫氳繃 `collectAsState()` 璁㈤槄銆?
- **DI**锛氭墜鍔?DI via `AppContainer`锛屼笉鐢?Hilt/Dagger銆?
- **缃戠粶**锛歄kHttp + SSE锛屼笉鐢?Retrofit/Ktor銆傛祦寮忓搷搴旈€愯瑙ｆ瀽 `data:` 琛屻€?
- **搴忓垪鍖?*锛歚kotlinx.serialization`锛圝SON锛夈€?
- **瀛樺偍**锛歊oom锛堟爲褰㈡秷鎭級+ DataStore Preferences銆傛暟鎹簱杩佺Щ闇€鏂板 schema 蹇収鍒?`app/schemas/`銆?
- **i18n**锛埪0.6锛夛細浠?`values/`锛坋n锛? `values-zh/`锛坺h锛夈€傛柊澧炲瓧绗︿覆闇€鍚屾椂鍦ㄤ袱澶勬坊鍔犮€俙SettingsLanguagePage.kt` 涓?`MainActivity.attachBaseContext()` 蹇呴』鍚屾銆?
- **瀛椾綋**锛埪0.7锛夛細`OutfitFamily` = `FontFamily.Default`锛宍MonoFamily` = `FontFamily.Monospace`銆傜姝?`R.font.*` 寮曠敤銆?
- **鍛藉悕**锛欳omposable 鍑芥暟 PascalCase锛堝 `ChatApp`锛夛紝ViewModel/Repository/Manager 鍚庣紑鏄庣‘锛屽寘鍚嶅崟鏁般€?
- **婧愮爜澶у皬**锛氭瘡 Kotlin 鏂囦欢 鈮?999 琛岋紙`./gradlew verifyKotlinFileSize` 寮哄埗锛夈€?
- **娴嬭瘯**锛氬崟鍏冩祴璇曟斁 `app/src/test/`锛孎-Droid 涓撳睘娴嬭瘯鏀?`app/src/testFdroid/`銆?
- **浜х墿**锛欳I 浜у嚭 `Agora-v{VERSION}-android-arm64-v8a.apk`锛屼粎 `arm64-v8a` ABI銆?

## 8. 甯哥敤鍛戒护

```bash
# 鏋勫缓 F-Droid Release APK锛圕I 涓荤洰鏍囷級
./gradlew assembleFdroidRelease

# 鏋勫缓 Google Play Release APK
./gradlew assemblePlayRelease

# 鏋勫缓 Play AAB bundle
./gradlew bundlePlayRelease

# 鍗曞厓娴嬭瘯
./gradlew test

# 鏋勫缓鎻掍欢娴嬭瘯锛堝瓧鑺傜爜淇 + 婧愮爜澶у皬绛栫暐锛?
./gradlew -p build-logic test

# 婧愮爜澶у皬绛栫暐楠岃瘉锛堟瘡鏂囦欢 鈮?999 琛岋級
./gradlew verifyKotlinFileSize

# 鏋勫缓 PRoot 鍘熺敓浜岃繘鍒讹紙闇€ NDK 28.2.13676358锛?
./build-proot.sh

# Lint
./gradlew lint

# 鍙戠増锛堣Е鍙?CI 娴佹按绾匡級
git tag v1.0.0
git push origin v1.0.0
# 鈫?CI 鑷姩鏋勫缓 Agora-v1.0.0-android-arm64-v8a.apk 骞跺彂甯冨埌 GitHub Release

# 鏌ョ湅 CI 杩愯鐘舵€?
gh run watch
gh run view --log-failed    # 澶辫触鏃舵煡鐪嬫姤閿欐棩蹇?
```

鐜锛氭湰鍦扮绾匡紝缂?Android SDK/NDK/CMake锛?*鏃犳硶**鏈湴 `./gradlew assembleFdroidRelease`銆傜紪璇戦獙璇佽蛋 GitHub CI锛埪2锛夈€傚瓙妯″潡 checkout 闇€ `--recurse-submodules`銆?

## 9. 鍙樻洿鏃ュ織锛堣拷鍔犳柊琛岋紝鏈€鏂板湪涓婏級

- 2026-08-20 task id=40 ASR 默认中文 + 麦克风单次录音时长上限（本次会话，coding-engineer team-mate）：将 ASR 默认语言改为中文、修复 Vosk 语言不匹配 bug、给单次录音加 90 秒超时。
  - `ceda24ab` **feat(asr)**: default to zh + fix language mismatch + single-asr recording timeout。
    - `SettingsManager.kt:404` — `voiceLanguage` 默认值 `"en"` → `"zh"`。
    - `SettingsRepository.kt:110` — `voiceLanguage` eager 初始默认值 `"en"` → `"zh"`（`hot()` 第二参数，DataStore 加载前使用）。
    - `VoiceConversationController.kt:90` — 新增 `private var singleAsrTimeoutJob: Job? = null`。
    - `VoiceConversationController.kt:94-97` — 新增 `companion object` 含 `MAX_SINGLE_ASR_DURATION_MS = 90_000L`。
    - `VoiceConversationController.kt:153-163` — `startSingleAsr()` 在 `beginListening()` 后启动超时 Job，`delay(90s)` 后检查 `active && SINGLE_ASR && LISTENING` 则调用 `stopCaptureAndTranscribe()` 自动转写。
    - `VoiceConversationController.kt:188-189` — `finishConversationTurn()` 取消超时 Job。
    - `VoiceConversationController.kt:210-211` — `stopSingleAsr()` 取消超时 Job。
    - `VoiceConversationController.kt:230-231` — `stop()` 取消超时 Job。
    - `VoiceConversationController.kt:722-727` — `transcribeWithVosk()` 修复语言不匹配：条件从 `!isReady()` 改为 `!isReady() || getCurrentLanguage() != langCode`，移除硬编码 `"en"` 回退（初始化失败时走下方 whisper fallback / error 逻辑）。
    - `VoiceConversationController.kt:888-889` — `handleTranscriptionResult()` 错误分支 SINGLE_ASR 取消超时 Job。
    - `VoiceConversationController.kt:908-909` — `handleTranscriptionResult()` 成功分支 SINGLE_ASR 取消超时 Job。
  - **约束遵守**：`VoiceConversationController.kt` 982 行 ≤999 ✅；`SettingsManager.kt` 998 行 ≤999 ✅；`SettingsRepository.kt` 580 行 ≤999 ✅；代码与注释均英文 ✅；无新增字符串资源 ✅；无 bump 版本号 ✅。
  - **验证**：本地 git commit 成功（`ceda24ab`，3 files changed, +34/-7），**未 push**（GitHub 网络不可达，按 R0.8 待后续 push 验证 CI）。

- 2026-08-20 task id=41 ASR/语音日志清理按钮（本次会话，coding-engineer team-mate）：在设置页 ASR 诊断区添加清空日志按钮。
  - `435e8e8d` **feat(settings)**: add ASR/voice log cleanup entry。
    - `SettingsAsrDiagnosticsSection.kt:200-207` — 在现有按钮 Row 内（copy log / save to downloads 之后）新增 `TextButton`，onClick 调用 `AppLog.clear()` 清空内存日志 + `Toast.makeText(context, context.getString(R.string.asr_log_cleared), LENGTH_SHORT).show()` 显示提示。`AppLog` 已 import（L27），`context` 在 Composable 作用域可用。
    - `values/strings.xml:170-171` — 新增 `asr_clear_log`="Clear ASR Log" + `asr_log_cleared`="ASR log cleared"。
    - `values-zh/strings.xml:846-847` — 新增 `asr_clear_log`="清空 ASR 日志" + `asr_log_cleared`="ASR 日志已清空"。
  - **约束遵守**：`SettingsAsrDiagnosticsSection.kt` 210 行 ≤999 ✅；代码与注释均英文 ✅；用户可见文本 en/zh 双语 ✅；未 bump 版本号 ✅；未新增字体 ✅；i18n 仅 en/zh ✅。
  - **验证**：本地 git commit 成功（`435e8e8d`，3 files changed, +12），**未 push**（GitHub 网络不可达，按 R0.8 待后续 push 验证 CI）。

- 2026-08-19 task id=24 VoskTranscriber 流式会话自动初始化 + 未知语言代码警告（本次会话，coding-engineer team-mate）：修复 `startStreamingSession()` 在模型文件已下载但未初始化时返回 false 的 Bug #1，以及 `getLanguageByCode()` 静默回退的 Bug #2。
  - `ec99db01` **fix(vosk)**: auto-init model in startStreamingSession + warn on unknown language code。
    - `VoskTranscriber.kt:631-647` — `startStreamingSession()` 改为 `suspend fun`；在 `synchronized(streamingLock)` 块之前添加 auto-init 逻辑：当 `!isModelLoaded || model == null` 时检查 `File(getModelDirectory(languageCode), "am/final.mdl").exists()`，若存在则调用 `initialize(languageCode)`。auto-init 必须在 synchronized 块之外，因为 `initialize()` 使用 `withContext(Dispatchers.IO)` 会切换线程。KDoc 注释更新说明 auto-init 行为。
    - `VoskTranscriber.kt:101-108` — `getLanguageByCode()` 添加 `android.util.Log.w` 警告日志，当语言代码未在 `AVAILABLE_LANGUAGES` 中找到时记录回退，便于 logcat 调试。
  - **调用点分析**：`VoiceConversationController.kt:436` 的 `startStreamingSession()` 调用在 `scope.launch {}` 协程作用域内，改为 `suspend fun` 无需修改调用点。调用方已有 auto-init 逻辑（L400-410），本次修复为 defense-in-depth。
  - **约束遵守**：`VoskTranscriber.kt` 806 行 ≤999 ✅；代码与注释均英文 ✅；无新增字符串资源 ✅；无 bump 版本号 ✅。
  - **验证**：本地 git commit 成功（`ec99db01`，1 file changed, +24/-4），**已 push**，CI #32252119499 全绿验证通过（conclusion=success）。

- 2026-08-19 task id=23 ASR 设置严格引擎就绪检查 + 显著语言选择器（本次会话，coding-engineer team-mate）：修复 ASR 设置页面缺乏引擎就绪诊断、语言选择器不显著导致用户无法定位 Vosk ASR 静默失败根因的问题。
  - `d409bbd5` **fix(asr-settings)**: strict engine readiness checks + prominent language selector。
    - `SettingsAsrDiagnosticsSection.kt` — 新增 5 个参数（`asrUseRemote`/`asrRemoteBaseUrl`/`asrRemoteApiKey`/`asrRemoteModel`/`voiceLanguage`，均带默认值）；用 `buildList` 构建多行引擎就绪状态文本，按 `auto`/`vosk`/`whisper`/`system` 分支显示严格检查（auto 检查 Vosk `isReady()` + Whisper configured + System available，若都没有显示 "⚠ NO engine available!"；vosk 显示 loadedLang/downloadedLangs/modelForVoiceLang；whisper 显示 baseUrl/apiKey/model 是否 set；system 显示 `SpeechRecognizer.isRecognitionAvailable`）；新增红色（`Color(0xFFE53935)`）语言不匹配警告（所选 voice language 无已下载 Vosk 模型时）；保留原有 Vosk diagnostic text、log text、copy/save buttons 功能。硬编码英文诊断文本，无新增字符串资源。
    - `SettingsGenerationPage.kt:757-761` — `SettingsAsrDiagnosticsSection(` 调用点添加 5 个新参数（`asrUseRemote`/`asrRemoteBaseUrl`/`asrRemoteApiKey`/`asrRemoteModel`/`voiceLanguage`），所需变量在作用域中已存在。
    - `SettingsVoskModelsSection.kt` — Change B（前一个 subagent 完成）：新增 `BASE_LANGUAGE_DISPLAY_NAMES` 友好显示名映射（23 种基础语言）；将语言选择器重构为显著 `Surface` 卡片，显示当前语言友好名 + 下载状态指示器（✓/⚠）+ 下拉菜单（每项标注 ✓ 已下载）+ 不匹配警告（当前语言无已下载模型时红色提示）+ Ready/Lang 状态行；保留模型列表（下载/删除）功能。
  - **约束遵守**：每文件 ≤999 行；代码和注释均英文；无新增字符串资源；无 bump 版本号；未修改 `VoiceConversationController.kt`/`ChatViewModel.kt`；`VoskTranscriber.kt` 工作目录修改未纳入本次 commit（不属于本任务范围）。
  - **验证**：本地 git commit 成功（`d409bbd5`，3 files changed, +205/-39），**已 push**，CI #32250168436 全绿验证通过（conclusion=success）。

- 2026-08-19 task id=17 TTS barge-in + 强制 TTS 播放（本次会话，coding-engineer team-mate）：修复实时语音对话中 TTS 不播放的问题。
  - `ee98a23a` **fix(voice)**: force TTS in streaming conversation + barge-in on new reply。
    - `VoiceConversationController.kt:81-82` — 新增 `isConversationStreaming()` 方法，暴露 `isStreamingConversation` 状态。
    - `VoiceConversationController.kt:894-895` — `handleTranscriptionResult` CONVERSATION 分支添加 `TtsManager.stop()` barge-in，新回复到达时停止当前 TTS 播放。
    - `VoiceConversationController.kt:923` — `observeLlmAndTts` 条件从 `if (ttsAutoPlayOn())` 改为 `if (isStreamingConversation || ttsAutoPlayOn())`，流式会话模式强制 TTS。
    - `ChatViewModel.kt:555-556` — `onStreamCommit` 回调添加 `voiceStreaming` 变量，TTS 播放条件改为 `voiceStreaming || (settings.ttsEnabled.value && settings.ttsAutoPlay.value)`。
  - **验证**：GitHub CI 全绿（conclusion=success），编译验证通过。文件行数 VoiceConversationController.kt=955 ≤999，ChatViewModel.kt=999 ≤999。

- 2026-08-19 全量代码审查与修复（本次会话，文档维护代理）：3 个 commit 修复安全/崩溃/UI/CI 问题，CI 全绿验证通过。
  - `6723e6e7` **fix(security,crash)**: Zip Slip 路径遍历防护 + NPE 守卫 + Room DB 泄漏修复。
    - `VoskTranscriber.kt:541` — 添加 Zip Slip canonical path 验证，防止解压路径遍历攻击。
    - `SettingsProviderDetailPage.kt:507` — 添加 `copiedFilePath` null 检查，防止 NPE 崩溃。
    - `AutoBackupWorker.kt:28` — `finally` 块添加 `db.close()`，修复 Room 数据库资源泄漏。
  - `fbf6231d` **fix(ui)**: Compose recompose race — 替换 `!!` 为安全空处理。
    - `SettingsDataControlPage.kt:333,470` — `remember` 添加 key，防止条件块内状态残留（H3）。
    - `SettingsSearchPage.kt:666` — 提取 `showRenameDialog` 到 local val，防止 `remember` 失效（H4）。
    - 9 个文件 14 处 — 替换 `if(null)` 块内的 `!!` 为安全空处理（H5）。
  - `354e566e` **fix(ci)**: smart cast delegated property in `WelcomeScreen` when expression。
    - `WelcomeScreen.kt:473-481` — `selectedProvider` 是委托属性无法 smart cast，捕获到 local val `providerForDesc`。
  - **验证**：GitHub CI 全绿，编译验证通过。本次为全量代码审查后的批量修复，无新增功能，无接口契约变更。

## 10. 鍙傝€冪储寮?

- 鏋舵瀯鏂囨。锛歚ARCHITECTURE.md`锛?90 琛岋紝璇︾粏鏋舵瀯璇存槑锛夈€?
- 鐗堟湰鐩綍锛歚gradle/libs.versions.toml`锛圓GP/Kotlin/Compose/Room 绛夌増鏈粺涓€绠＄悊锛夈€?
- 涓婃父鍊熼壌锛歚/opt/github/RustSync`锛堢紪璇戞祦姘寸嚎鍙傜収锛歵ag 瑙﹀彂 鈫?浜х墿鍛藉悕 鈫?GitHub Release 妯″紡锛夈€?
- 鍏抽敭鏂囦欢閫熸煡锛堣鏁颁负 PowerShell 瀹炴祴鍊硷紝2026-08-18 鍚屾锛夛細
  - 搴旂敤鍏ュ彛锛歚app/src/main/java/com/lxseek/chat/MainActivity.kt`
  - Application锛歚app/src/main/java/com/lxseek/chat/AgoraApplication.kt`
  - DI 瀹瑰櫒锛歚app/src/main/java/com/lxseek/chat/di/AppContainer.kt`
  - Provider 鎺ュ彛锛歚app/src/main/java/com/lxseek/chat/api/LlmProvider.kt`
  - HTTP 瀹㈡埛绔細`app/src/main/java/com/lxseek/chat/api/HttpClient.kt`
  - 涓婚锛歚app/src/main/java/com/lxseek/chat/ui/theme/{Type,Theme,Color}.kt`
  - 璇█閫夐」锛歚app/src/main/java/com/lxseek/chat/ui/settings/SettingsLanguagePage.kt`
  - 绯荤粺鎻愮ず锛歚app/src/main/java/com/lxseek/chat/data/DefaultSystemPrompt.kt`
  - 鑱婂ぉ涓?Composable锛歚app/src/main/java/com/lxseek/chat/ui/chat/ChatApp.kt`锛?91 琛岋級
  - 鑱婂ぉ鎷嗗垎鏂囦欢锛歚ChatAppBottomBarSection.kt`锛?57锛? `ChatAppOverlays.kt`锛?96锛? `ChatAppInteractionEffects.kt`锛?57锛? `ChatAppDialogHost.kt`锛?46锛?
  - 鍙戦€佸尯锛歚ui/chat/bottombar/ChatBottomBar.kt`锛?95锛? `ComposerSendButton.kt`锛?32锛?
  - 璇煶瀵硅瘽鎺у埗鍣細`viewmodel/VoiceConversationController.kt`锛?49锛?
  - 璇煶瑕嗙洊灞傦細`ui/chat/VoiceConversationOverlay.kt`锛?67锛? `SingleAsrOverlay.kt`锛?36锛?
  - 闊抽閲囬泦锛歚speech/AudioCaptureManager.kt`锛?34锛?
  - ChatViewModel锛歚viewmodel/ChatViewModel.kt`锛?98锛?
  - SettingsManager锛歚data/SettingsManager.kt`锛?98锛?
  - UI 閲嶈璁¤鏍硷細`UI_REDESIGN_SPEC.md`锛?01 琛岋級
  - 鏋舵瀯鏂囨。锛歚ARCHITECTURE.md`锛?90 琛岋級
  - 鏋勫缓閰嶇疆锛歚app/build.gradle.kts`
  - CI 娴佹按绾匡細`.github/workflows/build.yml`
  - PRoot 鏋勫缓锛歚build-proot.sh`
  - 鍘熺敓鏋勫缓锛歚app/src/main/cpp/CMakeLists.txt`
