package ai.pleos.pitstop

import ai.pleos.pitstop.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Deterministic demo inputs never reach the vehicle adapter. */
@Composable fun JourneyScenarios(close: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    var warmth by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf(false) }
    var parked by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    val now = 1_000_000L
    val mall = ToiletTiming(60, 60, false, 300, 120, 90, 30, now, "가상 대형 공공시설")
    val station = ToiletTiming(60, 0, true, 15, 30, 0, 15, now, "가상 공원 입구")
    val before = Comfort(Vent.FACE, 0, 0, 8, "WHITE")
    val care = CareLease(before, CareOptions(warmth, true, true), now)
    val current = care.target.copy(seatHeat=if(manual) 1 else care.target.seatHeat)
    val stages = listOf("TTT 비교", "선택형 케어", "도착 조건", "다음 후보 · 목적지 복귀", "주행 코칭")
    AlertDialog(onDismissRequest=close, title={Text("PitStop · 다섯 기능 체험")}, text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("가상 입력 시뮬레이션 · 실제 차량 제어, 음성 인식, 휴대폰 전송은 실행하지 않아요.")
            Text("${step+1}/5  ${stages[step]}",style=MaterialTheme.typography.titleLarge)
            when(step) {
                0 -> {
                    Text("300m 대형 공공시설: ${mall.total(now)!!/60}분\n800m 공원 입구: ${station.total(now)!!/60}분 → 1순위")
                    Text("주행 + 미포함 신호 대기 + 주차 + 도보 + 층간 이동 + 대기. ETA에 포함된 신호는 다시 더하지 않아요.")
                    Text("공원 입구: 60 + 0 + 15 + 30 + 0 + 15 = 120초. 실제 시설 시간·주차 허용 여부는 별도 확인이 필요해요.")
                }
                1 -> {
                    Row { Checkbox(warmth,{warmth=it}); Text("온열 사용 선택 · 최대 3분") }
                    Row { Checkbox(manual,{manual=it}); Text("운전자가 시트 1단으로 직접 변경") }
                    Text("송풍 ${current.vent} · 시트 ${current.seatHeat} · 핸들 ${current.wheelHeat}\n미디어 ${current.media} · 조명 ${current.ambient}")
                    care.confirm(care.target); care.observe(current)
                    Text("종료 복원: ${care.restore(current)}\n수동 변경한 항목은 유지해요. 온열은 치료 기능이 아니에요.")
                }
                2 -> {
                    Row { Checkbox(parked,{parked=it}); Text("P단 + 완전 정차 + 안전벨트 해제") }
                    val actions = ArrivalGate().evaluate(ArrivalSignal(10.0,5.0,0,if(parked)0.0 else 5.0,0,parked,parked,true),ArrivalConsent(true,true,true))
                    Text(if(actions.isEmpty()) "자동화 대기: 이동 중에는 실행하지 않아요." else "요청 생성: 창문 닫기 / 허용된 정차 구역 비상등 / 잠금 해제 / 입구 안내")
                    Text("거리 10m · 위치 오차 5m. 실제 차량 동작의 성공은 각 장치 응답으로 확인해야 해요.")
                }
                3 -> {
                    Row { Checkbox(unavailable,{unavailable=it}); Text("첫 후보가 닫혀 있어요") }
                    Row { Checkbox(resume,{resume=it}); Text("원래 목적지로 요청") }
                    Text(if(resume) "기억한 원래 목적지로 재탐색 요청 → 목적지 좌표 확인 후 탐색 정보 삭제" else if(unavailable) "첫 후보를 이번 탐색에서 제외 → 현재 위치로 다음 공중화장실 계산" else "원래 목적지 확인 → 첫 공중화장실 주차장으로 안내")
                    Text("닫힌 곳은 이번 탐색 동안 계속 제외해요. 남은 후보가 없으면 이를 알리고, 자동으로 같은 곳을 다시 안내하지 않아요.")
                    Text("개인 이용 시각·횟수·체류시간을 기록하지 않아요. 이 화면은 가상 시연이에요.")
                }
                4 -> {
                    val coach=SmoothCoach()
                    Text(coach.hazard(RoadHazard("demo-bump",HazardKind.BUMP,40.0,now),now,30.0).orEmpty())
                    Text(coach.acceleration(-3.0,now,0).orEmpty())
                    Text("같은 위험 구간은 한 번, 페달 코칭은 최소 15초 간격. 오래된 구간·센서 정보는 사용하지 않아요.")
                }
            }
        }
    },confirmButton={TextButton(onClick={step=(step+1)%5}){Text(if(step==4)"처음으로" else "다음")}},
        dismissButton={TextButton(onClick=close){Text("닫기")}})
}
