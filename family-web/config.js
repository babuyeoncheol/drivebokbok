// 가족 앱 서버 설정. Supabase 대시보드의 Connect 창(또는 Settings → API Keys)에 있는 값을 넣는다.
// 공개 키는 원래 브라우저에 들어가도 되는 키다(읽기는 차 코드를 아는 함수로만 열려 있음).
// 비워 두면 '체험 모드'만 쓸 수 있고, 앱의 설정 화면에서 직접 넣을 수도 있다.
window.ANSIM_CONFIG = {
  supabaseUrl: "",  // 예: "https://abcd.supabase.co"
  supabaseKey: ""   // 예: "sb_publishable_..." 또는 예전 anon 키
};
