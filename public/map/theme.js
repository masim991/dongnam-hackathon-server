// 지도 색·설정을 한곳에 모은 파일. 디자인을 바꿀 때 여기와 styles.css 만 고치면 됩니다(로직은 app.js).
// 이 파일의 색은 지도 레이어에 쓰이고, applyTheme() 이 같은 값을 CSS 변수(--c-이름)로도 내보내 범례 색이 항상 일치합니다.

export const CONFIG = {
  // 타일 스타일 주소. 타일 서버를 바꿀 때 여기만 수정한다.
  STYLE_URL: 'https://tiles.openfreemap.org/styles/positron',
  // 대한민국 대략 범위 [서, 남, 동, 북] (독도·제주·마라도 포함, 행정경계 아님)
  BOUNDS: [[124.0, 32.8], [132.2, 38.8]],
  START: { center: [129.03, 35.12], zoom: 11 },
};

export const THEME = {
  // 코스·횡단·신호
  course: '#1849a9',          // 선택한 코스 선
  crosswalk: '#d92d20',       // 신호등 있는 횡단보도(공공 데이터)
  controller: '#1570ef',      // 신호제어기(교차로 단위)
  osmSignal: '#f79009',       // OSM 신호 횡단(참고)
  osmOther: '#667085',        // OSM 무신호·미분류 횡단(참고)
  // 코스 선형 정리 표시
  bridge: '#dc6803',          // 끊긴 곳을 잇는 점선 연결선
  issueFill: '#fef0c7',       // 코스 선형 문제 마커 안쪽
  issueStroke: '#b54708',     // 코스 선형 문제 마커 테두리
  gapMarker: '#f04438',       // 산책로 끊김 표시
  roundabout: '#7a5af8',      // 회전교차로 고리 표시
  // 경사 크기: 10~15 / 15~20 / 20~40 / 40% 이상
  slopeLow: '#b7791f',
  slopeModerate: '#c65d16',
  nearby: '#667085',
  slopeMid: '#f04438',
  slopeHigh: '#7a271a',
  // 부울경 산책로·산길 벡터 종류별
  trail: '#567064',
  trailSteps: '#9e77ed',
  trailTrack: '#a15c07',
  trailCycleway: '#0e9384',
  // 내 러닝 기록(GPX)
  myrun: '#067647',
  // 공통
  white: '#ffffff',
  ink: '#101828',
};

export function applyTheme(root = document.documentElement) {
  for (const [name, value] of Object.entries(THEME)) root.style.setProperty('--c-' + name, value);
}
