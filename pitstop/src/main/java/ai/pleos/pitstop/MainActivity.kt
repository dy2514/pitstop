package ai.pleos.pitstop

import ai.pleos.pitstop.core.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import java.time.*

class PitStopSessionModel : ViewModel() { val journey = DetourSession() }

class MainActivity:ComponentActivity() {
    private val session by lazy { ViewModelProvider(this)[PitStopSessionModel::class.java].journey }
    private var catalog by mutableStateOf(Catalog("시설 정보 불러오는 중",false,emptyList(),null))
    private var demo by mutableStateOf(false)
    private var parked by mutableStateOf(false)
    private var point:GeoPoint?=null
    private var fixAt=0L
    private var candidates by mutableStateOf<List<Candidate>>(emptyList())
    private var message by mutableStateOf("주차 후, 가까운 화장실과 주차장을 함께 찾아요.")
    private var navStatus by mutableStateOf("차량 연결 확인 중")
    private var priority by mutableStateOf(Priority.FASTEST_TOILET)
    private var accessible by mutableStateOf(false)
    private var onlyKnown by mutableStateOf(false)
    private val demoExcluded=mutableSetOf<String>()
    private var navigator:PleosNavigator?=null
    private var gate:CarGate?=null
    private var clock:Job?=null
    private var care: PitStopCare? = null
    private var voiceJob: Job? = null
    private var voiceStatus by mutableStateOf("")
    private var careStatus by mutableStateOf("온열은 선택 사항이에요. 송풍·오디오·도착 매크로는 체험 화면에서 확인할 수 있어요.")
    private fun startCare() {
        if(!parked || care != null) return
        care = PitStopCare(this) { careStatus=it; if(it.startsWith("온열 종료") || it.startsWith("일부 복원")) care=null }.also { it.start() }
    }
    private var locationFresh by mutableStateOf(false)
    private val prefs by lazy { getSharedPreferences("pitstop_catalog",MODE_PRIVATE) }
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){connect()}
    private val importer=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null && parked) lifecycleScope.launch {
            runCatching {
                val text=withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use{val b=it.readNBytes(1_000_001);require(b.size<=1_000_000);b.toString(Charsets.UTF_8)} ?: error("파일을 읽지 못했어요.") }
                val parsed=CatalogParser.parse(text);require(!parsed.demo) { "실제 시설 파일을 선택해 주세요." }
                withContext(Dispatchers.IO){check(prefs.edit().putString("catalog",text).commit())}
                catalog=parsed;demo=false;candidates=emptyList();message="시설 ${parsed.pairs.size}개를 불러왔어요. 현재 위치로 검색해 주세요."
            }.onFailure{message="시설 파일을 확인해 주세요 · ${it.message.orEmpty().take(100)}"}
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        loadReal()
        setContent { PitStopScreen(catalog,demo,parked,candidates,message,navStatus,locationFresh,priority,accessible,onlyKnown,
            { search() },{ toggleDemo() },{ importer.launch(arrayOf("application/json","text/plain","application/octet-stream")) },
            { priority=it;candidates=emptyList() },{accessible=it;candidates=emptyList()},{onlyKnown=it;candidates=emptyList()},
            {candidate->navigate(candidate)},{id->if(demo)demoExcluded+=id else session.exclude(id);search()},careStatus,{startCare()},{care?.stop()},voiceStatus,session.currentStop!=null,session.original!=null,{screenJourneyAction(false)},{screenJourneyAction(true)}) }
        val needed=listOf("pleos.car.permission.CAR_CLIMATE","pleos.car.permission.CONTROL_CAR_CLIMATE","pleos.car.permission.CAR_SEATS","pleos.car.permission.CONTROL_CAR_SEATS","pleos.car.permission.CAR_LIGHTS","pleos.car.permission.CONTROL_CAR_LIGHTS", "android.car.permission.CAR_POWERTRAIN","pleos.car.permission.NAVI_ROUTE","pleos.car.permission.NAVI_ROUTE_SEARCH").filter {
            checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED && runCatching{packageManager.getPermissionInfo(it,0).protection==PermissionInfo.PROTECTION_DANGEROUS}.getOrDefault(false)
        }
        if(savedInstanceState==null)receiveGleo(intent,needed.isNotEmpty())
        if(needed.isNotEmpty())permissions.launch(needed.toTypedArray())
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveGleo(intent, false)
    }
    private fun receiveGleo(incoming: Intent, setupRequired: Boolean) {
        if(incoming.action !in PitStopVoiceCommand.actions) return
        setIntent(Intent(this,MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        val call=GleoCall(this,incoming)
        val command=try { PitStopVoiceCommand.parse(call.parameters(PitStopVoiceCommand.parameters),call.action) }
        catch(e:GleoInput){call.reply(GleoResult(e.status,e.message));return}
        if(setupRequired){call.reply(GleoResult("fail","Complete initial permission setup while parked, then repeat the voice command."));return}
        if(voiceJob?.isActive==true || navigator?.busy==true){call.reply(GleoResult("fail","A previous navigation request is still being confirmed."));return}
        voiceStatus="음성 요청을 처리하고 있어요."
        call.startDeadline()
        voiceJob=lifecycleScope.launch {
            try {
                val result=withTimeout(5_500) {
                    if(!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
                        return@withTimeout GleoResult("fail","Vehicle navigation is unavailable in this environment.")
                    val nav=navigator ?: return@withTimeout GleoResult("fail","Vehicle navigation is unavailable.")
                    if(command.action==PitStopVoiceCommand.RESUME) {
                        if(session.original==null) return@withTimeout GleoResult("fail","No original destination was remembered in this session. Set a destination in the navigation app.")
                        if(!call.canBegin()) return@withTimeout GleoResult("fail","The request expired.")
                        nav.resumeOriginal()
                        return@withTimeout GleoResult("success","Navigation to the original destination was requested; destination confirmation is pending.")
                    }
                    // A real voice request never uses the example location or facilities.
                    if(demo){demo=false;loadReal();candidates=emptyList()}
                    if(command.alternative) {
                        if(session.currentStop==null) return@withTimeout GleoResult("asking","There is no current public toilet stop. Ask to find a restroom first.")
                        session.markUnavailable()
                    } else accessible=command.accessible
                    while(point==null || System.currentTimeMillis()-fixAt !in 0..30_000)delay(100)
                    ensureActive()
                    val origin=point ?: return@withTimeout GleoResult("fail","A current vehicle location is unavailable.")
                    priority=Priority.FASTEST_TOILET
                    val found=session.search(origin,fixAt,catalog.pairs,ZonedDateTime.now(ZoneId.of("Asia/Seoul")),SearchOptions(priority,accessible,!onlyKnown))
                    candidates=found.candidates;message=found.message
                    val best=found.candidates.firstOrNull{it.pair.coordinatesVerified && it.pair.eligiblePublicToilet}
                        ?: return@withTimeout GleoResult("fail","No other eligible public toilet was found. Unavailable stops remain excluded for this session.")
                    if(!call.canBegin())return@withTimeout GleoResult("fail","The request expired before navigation could be requested.")
                    nav.route(best.pair)
                    command.requested(best)
                }
                voiceStatus=if(result.status=="success")"음성 요청 전달 완료 · 내비 결과 확인 중" else "요청 조건을 확인해 주세요. 이용 불가 후보와 원래 목적지 정보는 유지해요."
                call.reply(result)
            } catch(_:TimeoutCancellationException) {
                voiceStatus="위치 또는 원래 목적지를 제시간에 확인하지 못했어요. 다시 요청해 주세요."
                call.reply(GleoResult("fail","Current location or original navigation destination could not be confirmed in time."))
            } catch(e:CancellationException) {
                call.reply(GleoResult("fail","The request was interrupted."));throw e
            } catch(_:Exception) {
                voiceStatus="내비 요청을 완료하지 못했어요. 차량 연결 상태를 확인해 주세요."
                call.reply(GleoResult("fail","The navigation request could not be completed. Remembered journey state was retained."))
            }
        }
    }
    private fun screenJourneyAction(resume: Boolean) {
        if(!parked || demo || voiceJob?.isActive==true || navigator?.busy==true) return
        voiceJob=lifecycleScope.launch {
            runCatching {
                val nav=navigator ?: error("내비 연결을 확인해 주세요.")
                if(resume) nav.resumeOriginal() else {
                    session.markUnavailable()
                    val origin=point ?: error("현재 위치를 확인해 주세요.")
                    val result=session.search(origin,fixAt,catalog.pairs,ZonedDateTime.now(ZoneId.of("Asia/Seoul")),SearchOptions(priority,accessible,!onlyKnown))
                    candidates=result.candidates;message=result.message
                    val next=result.candidates.firstOrNull{it.pair.coordinatesVerified} ?: error("다른 공중화장실 후보가 없어요. 제외한 시설은 다시 안내하지 않아요.")
                    nav.route(next.pair)
                }
            }.onFailure { navStatus=it.message ?: "내비 연결을 확인해 주세요." }
        }
    }
    private fun loadReal() {
        runCatching { CatalogParser.parse(prefs.getString("catalog",null) ?: assets.open("catalog.json").bufferedReader().use{it.readText()}).also{require(!it.demo)} }
            .onSuccess{catalog=it}.onFailure{prefs.edit().remove("catalog").apply();catalog=Catalog("시설 파일 확인 필요",false,emptyList(),null);message="시설 정보를 읽지 못했어요. 시설 파일을 다시 불러와 주세요."}
    }
    override fun onStart(){super.onStart();connect();clock=lifecycleScope.launch{while(isActive){locationFresh=System.currentTimeMillis()-fixAt in 0..30_000;delay(1000)}}}
    private fun connect(){
        gate?.close();parked=false
        if(!packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)){navStatus="Pleos 에뮬레이터에서 실행해 주세요.";return}
        gate=CarGate(this){parked=it}.also{it.start()}
        if(navigator==null) navigator=PleosNavigator(this,session,{p,t->point=p;fixAt=t;locationFresh=true},{navStatus=it}).also{it.start()}
    }
    private fun toggleDemo(){
        if(!parked)return
        demo=!demo;candidates=emptyList();demoExcluded.clear()
        if(demo){catalog=CatalogParser.parse(assets.open("demo.json").bufferedReader().use{it.readText()});message="예제 시연 · 가상 시설이며 실제 길 안내를 실행하지 않아요.";search()}
        else {loadReal();message="실제 차량 위치로 검색해 주세요."}
    }
    private fun search(){
        if(!parked)return
        val origin=if(demo)catalog.origin else point
        if(origin==null){message="현재 위치를 기다리고 있어요. 내비 권한과 연결을 확인해 주세요.";return}
        val now=if(demo)ZonedDateTime.of(2026,11,15,14,0,0,0,ZoneId.of("Asia/Seoul")) else ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
        val result=PitStopEngine.search(origin,if(demo)now.toInstant().toEpochMilli() else fixAt,catalog.pairs.filter{it.id !in (if(demo)demoExcluded else session.excludedIds)},now,
            SearchOptions(priority,accessible,!onlyKnown))
        candidates=result.candidates;message=result.message
        if(catalog.pairs.isEmpty())message="등록된 시설이 없어요. 검증한 시설 파일을 불러와 주세요."
    }
    private fun navigate(candidate:Candidate){
        if(!parked)return
        if(demo){navStatus="예제: ${candidate.pair.parkingName} 선택 완료 · 실제 내비 변경 없음";return}
        if(System.currentTimeMillis()-fixAt !in 0..30_000){message="현재 위치를 새로 확인한 후 다시 선택해 주세요.";return}
        val fresh=PitStopEngine.search(point ?: return,fixAt,listOf(candidate.pair),ZonedDateTime.now(ZoneId.of("Asia/Seoul")),SearchOptions(priority,accessible,!onlyKnown))
        if(fresh.candidates.isEmpty()){message="운영 상태가 바뀌었어요. 다시 검색해 주세요.";candidates=emptyList();return}
        if(voiceJob?.isActive==true || navigator?.busy==true) return
        voiceJob=lifecycleScope.launch {
            runCatching{navigator?.route(candidate.pair) ?: error("내비 연결을 확인해 주세요.")}.onFailure{navStatus=it.message.orEmpty()}
        }
    }
    override fun onStop(){care?.stop();clock?.cancel();gate?.close();parked=false;super.onStop()}
    override fun onDestroy(){voiceJob?.cancel();navigator?.close();super.onDestroy()}
}

private val Accent=Color(0xFFA7472D)
private val Ink=Color(0xFF302D27)
private val Muted=Color(0xFF716D64)
private val Paper=Color(0xFFF8F5EF)
private val Asta=FontFamily(Font(R.font.asta_sans))

@Composable private fun PitStopScreen(catalog:Catalog,demo:Boolean,parked:Boolean,items:List<Candidate>,message:String,nav:String,fresh:Boolean,
    priority:Priority,accessible:Boolean,onlyKnown:Boolean,search:()->Unit,toggleDemo:()->Unit,import:()->Unit,
    changePriority:(Priority)->Unit,changeAccessible:(Boolean)->Unit,changeKnown:(Boolean)->Unit,route:(Candidate)->Unit,exclude:(String)->Unit,careStatus:String,startCare:()->Unit,stopCare:()->Unit,
    voiceStatus:String,hasStop:Boolean,canResume:Boolean,nextStop:()->Unit,resumeDestination:()->Unit) {
    var confirmCare by remember { mutableStateOf(false) }
    var scenarios by remember { mutableStateOf(false) }
    var confirm by remember{mutableStateOf<Candidate?>(null)}
    MaterialTheme(colorScheme=lightColorScheme(primary=Accent,background=Paper,surface=Color.White,onSurface=Ink)) {
        Surface(Modifier.fillMaxSize(),color=Paper) {
            Column(Modifier.fillMaxSize().padding(28.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("PitStop",fontFamily=Asta,fontSize=30.sp,fontWeight=FontWeight.Bold)
                    Spacer(Modifier.width(18.dp));Text("주차부터 화장실까지",fontFamily=Asta,fontSize=19.sp,color=Muted)
                    Spacer(Modifier.weight(1f));Text(if(demo)"예제 시연" else if(fresh)"차량 위치 연결됨" else "차량 위치 확인 중",fontFamily=Asta,color=Accent,fontSize=16.sp)
                }
                if (parked) {
                    Row {
                        TextButton(onClick={scenarios=true}) { Text("기능 시나리오") }
                        TextButton(onClick=nextStop,enabled=hasStop && !demo) { Text("여기 닫혔어요 · 다음 후보") }
                        TextButton(onClick=resumeDestination,enabled=canResume && !demo) { Text("원래 목적지로") }
                        TextButton(onClick={confirmCare=true}) { Text("선택형 온열 켜기") }
                        TextButton(onClick=stopCare) { Text("온열 종료·복원") }
                    }
                    Text(careStatus,style=MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(20.dp))
                if(!parked) {
                    Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("음성으로 정차할 곳을 요청하세요",fontFamily=Asta,fontSize=38.sp,fontWeight=FontWeight.Bold)
                        Text(voiceStatus.ifBlank{"글레오에 ‘피트스탑, 화장실 안내해줘’라고 말씀해 주세요. 화면 조작은 주차 후 가능해요."},fontFamily=Asta,fontSize=20.sp,color=Muted,modifier=Modifier.padding(top=16.dp))
                        Text(nav,fontFamily=Asta,fontSize=17.sp,color=Muted,modifier=Modifier.padding(top=20.dp))
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text("공중화장실, 차 세울 곳까지.",fontFamily=Asta,fontSize=36.sp,fontWeight=FontWeight.SemiBold)
                        Text(if(demo)"가상 시설 · 고정 위치 · 실제 내비 요청 없음" else catalog.label,fontFamily=Asta,fontSize=17.sp,color=Muted,modifier=Modifier.padding(top=8.dp))
                        Row(Modifier.padding(vertical=18.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                            FilterChip(selected=priority==Priority.FASTEST_TOILET,onClick={changePriority(Priority.FASTEST_TOILET)},label={Text("TTT 짧은 순",fontFamily=Asta)})
                            FilterChip(selected=priority==Priority.NEARBY,onClick={changePriority(Priority.NEARBY)},label={Text("가까운 주차장",fontFamily=Asta)})
                            FilterChip(selected=priority==Priority.LESS_WALKING,onClick={changePriority(Priority.LESS_WALKING)},label={Text("적게 걷기",fontFamily=Asta)})
                            FilterChip(selected=accessible,onClick={changeAccessible(!accessible)},label={Text("장애인 화장실",fontFamily=Asta)})
                            FilterChip(selected=onlyKnown,onClick={changeKnown(!onlyKnown)},label={Text("운영시간 확인된 곳",fontFamily=Asta)})
                        }
                        Text(message,fontFamily=Asta,fontSize=20.sp,color=Ink)
                        Spacer(Modifier.height(14.dp))
                        items.forEachIndexed { index,candidate ->
                            CandidateRow(index,candidate,demo,{confirm=candidate},{exclude(candidate.pair.id)})
                            Spacer(Modifier.height(12.dp))
                        }
                        Text("거리는 주차장까지의 직선거리예요. 실제 주행 경로·시간은 기본 내비에서 확인해요.\n빈자리와 화장실 대기 인원은 실시간으로 확인하지 못해요.",fontFamily=Asta,fontSize=16.sp,lineHeight=24.sp,color=Muted,modifier=Modifier.padding(top=10.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick=toggleDemo,modifier=Modifier.heightIn(min=56.dp)){Text(if(demo)"실제 위치로 돌아가기" else "예제로 둘러보기",fontFamily=Asta,fontSize=17.sp)}
                            TextButton(onClick=import,modifier=Modifier.heightIn(min=56.dp)){Text("시설 파일 불러오기",fontFamily=Asta,fontSize=17.sp)}
                        }
                    }
                    Text(nav,fontFamily=Asta,fontSize=16.sp,color=Muted,modifier=Modifier.padding(vertical=12.dp))
                    Button(onClick=search,modifier=Modifier.fillMaxWidth().height(68.dp),shape=RoundedCornerShape(18.dp)) {
                        Text(if(demo)"예제 후보 다시 찾기" else "공중화장실 + 주차장 찾기",fontFamily=Asta,fontSize=23.sp)
                    }
                }
            }
        }
        if(confirmCare && parked) AlertDialog(onDismissRequest={confirmCare=false},title={Text("온열을 사용할까요?")},text={Text("지원되는 운전석 시트·핸들 열선을 최대 3단계로 3분간 적용해요. 화면을 나가거나 종료를 누르면 복원하고, 직접 변경한 설정은 유지해요. 불편하거나 뜨거우면 즉시 종료해 주세요. 치료 기능이 아니에요.")},confirmButton={TextButton(onClick={confirmCare=false;startCare()}){Text("사용")}},dismissButton={TextButton(onClick={confirmCare=false}){Text("취소")}})
        if(scenarios && parked) JourneyScenarios { scenarios=false }
        confirm?.takeIf{parked}?.let { candidate ->
            AlertDialog(onDismissRequest={confirm=null},title={Text("${candidate.pair.parkingName}으로 안내할까요?",fontFamily=Asta)},
                text={Text(if(demo)"예제 시연이에요. 실제 목적지는 바뀌지 않아요." else "기존 목적지를 이번 탐색 동안 기억하고 이 주차장으로 안내해요. 이용 후 원래 목적지로 다시 안내할 수 있어요. 빈자리와 현장 운영 여부는 확인이 필요해요.\n\n${candidate.pair.walkingNote}",fontFamily=Asta,fontSize=19.sp)},
                confirmButton={TextButton(onClick={route(candidate);confirm=null},modifier=Modifier.heightIn(min=56.dp)){Text("주차장으로 안내",fontFamily=Asta,fontSize=20.sp)}},
                dismissButton={TextButton(onClick={confirm=null},modifier=Modifier.heightIn(min=56.dp)){Text("취소",fontFamily=Asta,fontSize=20.sp)}})
        }
    }
    LaunchedEffect(parked){if(!parked){confirm=null;scenarios=false;confirmCare=false}}
}

@Composable private fun CandidateRow(index:Int,c:Candidate,demo:Boolean,select:()->Unit,exclude:()->Unit) {
    Surface(color=Color.White,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,Color(0xFFE7DED2))) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("0${index+1}",fontFamily=Asta,fontSize=28.sp,color=Accent,modifier=Modifier.padding(end=18.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.pair.toiletName,fontFamily=Asta,fontSize=25.sp,fontWeight=FontWeight.SemiBold)
                    Text("P  ${c.pair.parkingName}",fontFamily=Asta,fontSize=18.sp,color=Muted)
                }
                Text(if(c.straightMeters<1000)"${c.straightMeters.toInt()}m" else "%.1fkm".format(c.straightMeters/1000),fontFamily=Asta,fontSize=28.sp,color=Accent)
            }
            Text(c.tttSeconds?.let { "변기 도달 약 ${(it + 59) / 60}분 · 추정" } ?: "TTT 미확인 · 주행/주차/도보 시간 필요",fontFamily=Asta,fontSize=18.sp,color=Accent)
            Text(c.pair.address,fontFamily=Asta,fontSize=16.sp,color=Muted,modifier=Modifier.padding(top=12.dp))
            Text("${if(c.open==OpenState.OPEN)"등록 운영시간 내" else "운영시간 미확인"} · ${c.pair.fee} · ${c.pair.walkingMeters?.let{"도보 약 ${it}m"} ?: "도보거리 미확인"}",fontFamily=Asta,fontSize=18.sp,modifier=Modifier.padding(top=6.dp))
            Text(c.pair.walkingNote,fontFamily=Asta,fontSize=16.sp,color=Muted,modifier=Modifier.padding(top=6.dp))
            Text(c.warnings.joinToString(" · "),fontFamily=Asta,fontSize=15.sp,color=Accent,modifier=Modifier.padding(top=8.dp))
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(top=10.dp)) {
                Text("출처 ${c.pair.source} · ${c.pair.sourceDate}",fontFamily=Asta,fontSize=12.sp,color=Muted,modifier=Modifier.weight(1f))
                TextButton(onClick=exclude,modifier=Modifier.heightIn(min=56.dp)){Text("다른 곳",fontFamily=Asta,fontSize=17.sp)}
                Button(onClick=select,enabled=demo || c.pair.coordinatesVerified,modifier=Modifier.height(58.dp),shape=RoundedCornerShape(14.dp)) {
                    Text(if(demo)"선택해 보기" else if(c.pair.coordinatesVerified)"주차장으로 안내" else "시설 좌표 확인 필요",fontFamily=Asta,fontSize=18.sp)
                }
            }
        }
    }
}
