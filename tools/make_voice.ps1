# 안내음 녹음 파일 10개를 Windows 음성(한국어)으로 만든다.
# 실행: 저장소 맨 위 폴더에서  powershell -ExecutionPolicy Bypass -File tools\make_voice.ps1
# 결과: app\src\main\res\raw\cue_*.wav  → 앱을 다시 Run 하면 TTS 대신 이 파일을 튼다.
# 문장은 app/src/main/java/kr/ansimgwiga/car/core/Model.kt 의 Cue 와 같다.

Add-Type -AssemblyName System.Speech
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer

$korean = $synth.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'ko-KR' } | Select-Object -First 1
if (-not $korean) {
    Write-Host '한국어 음성이 없어요. 설정 > 시간 및 언어 > 음성(Speech)에서 한국어 음성을 추가한 뒤 다시 실행해 주세요.' -ForegroundColor Red
    Write-Host '설치된 음성:'; $synth.GetInstalledVoices() | ForEach-Object { Write-Host ('  ' + $_.VoiceInfo.Name + ' (' + $_.VoiceInfo.Culture.Name + ')') }
    exit 1
}
$synth.SelectVoice($korean.VoiceInfo.Name)
$synth.Rate = 0      # -10(느림) ~ 10(빠름)
$synth.Volume = 100

$cues = [ordered]@{
    'cue_belt'        = '안전벨트를 매 주세요.'
    'cue_maintenance' = '차량 점검 경고가 있어요. 운행 후 확인해 주세요.'
    'cue_energy'      = '연료나 배터리가 부족해요.'
    'cue_rest'        = '오래 운전하셨어요. 잠시 쉬어 가세요.'
    'cue_rear_seat'   = '뒷좌석을 확인해 주세요.'
    'cue_slow_down'   = '급제동이 잦아요. 천천히 가세요.'
    'cue_cheer_safe'  = '가족 메시지예요. 조심히 와요.'
    'cue_cheer_slow'  = '가족 메시지예요. 천천히 와도 괜찮아요.'
    'cue_cheer_call'  = '가족 메시지예요. 도착하면 연락 줘요.'
    'cue_cheer_love'  = '가족 메시지예요. 사랑해요, 기다리고 있어요.'
}

$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'app\src\main\res\raw'
New-Item -ItemType Directory -Force -Path $out | Out-Null

foreach ($name in $cues.Keys) {
    $file = Join-Path $out ($name + '.wav')
    $synth.SetOutputToWaveFile($file)
    $synth.Speak($cues[$name])
    Write-Host ('만듦: ' + $file)
}
$synth.SetOutputToNull()
$synth.Dispose()
Write-Host ('음성: ' + $korean.VoiceInfo.Name + ' · 10개 완료. Android Studio에서 다시 Run 하세요.') -ForegroundColor Green
