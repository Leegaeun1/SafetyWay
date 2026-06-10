<div>

  # 안심로
  **안전경로로 안내해주는 길 찾기 서비스**

  <br/>

  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=Kotlin&logoColor=white"/>
  <img src="https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=Android&logoColor=white"/>
  <img src="https://img.shields.io/badge/firebase-ffca28?style=for-the-badge&logo=firebase&logoColor=black"/>
  <img src="https://img.shields.io/badge/Naver Map-03C75A?style=for-the-badge&logo=naver&logoColor=white"/>
  <img src="https://img.shields.io/badge/TMap API-E8343A?style=for-the-badge&logo=sk&logoColor=white"/>
</div>

<br/>

## 1. 프로젝트 개요

기존 지도 앱(네이버 지도, 카카오맵)의 길찾기는 최단 경로 위주로 안내하며, CCTV와 보안등 위치를 고려한 경로 추천 기능을 제공하지 않습니다. 실제로 야간 보행 안전에 대한 사회적 우려가 증가하는 가운데, 낯선 동네에서 안전한 길을 스스로 판단하기 어렵다는 문제가 있습니다.
안심로는 CCTV와 보안등의 밀도를 기반으로 안전 점수를 계산하여, 단순히 빠른 길이 아닌 안전한 길을 추천해주는 보행 안내 앱입니다. 또한 SOS 사이렌, 가짜 통화, CCTV 사각지대 알림 등 위급 상황에 대응할 수 있는 기능을 함께 제공합니다.

* 개발 기간 : 2026.04~2026.06
* 참여 인원 : 1명

## 2. Android 지원 버전

* 최소 지원 Android 버전 : API 24
* 컴파일 SDK 버전 : API 35

## 3. 주요 기능

구현한 시스템의 핵심 기능은 다음과 같습니다.

* **안심 경로 탐색:** 최단 경로가 아닌, CCTV와 보안등의 밀도에 따라 안전점수를 계산하여 안심 경로를 추천하여 안내합니다.
* **CCTV 사각지대 알림:** 일정 거리동안 CCTV가 없다면 알림을 띄워줍니다.
* **CCTV/보안등/파출소 위치 표시:** 버튼을 통해 주변의 CCTV와 보안등, 파출소 위치를 확인할 수 있습니다.
* **비상 사이렌 & 가짜 통화:** 버튼을 통해 경보를 울릴 수 있으며(현재는 비명소리), 가짜 통화 설정을 통해 범죄 의도를 억제할 수 있습니다.
* **CCTV/보안등 위치 제보:** CCTV나 보안등의 위치가 제공되지 않는 지역은 사용자가 직접 촬영하여 제보할 수 있습니다.
<br/>

## 4. 시스템 아키텍처 
<img width="784" height="564" alt="image" src="https://github.com/user-attachments/assets/00a95f9b-fade-4823-abd4-b4397a8fc301" />

<br/>

## 5. 안전점수 계산 방식

경로를 **30m 간격**으로 샘플링한 뒤, 각 점 반경 **80m 이내**의 CCTV/보안등을 수집하여 아래 공식으로 점수를 산출합니다.

```
밀도 = (CCTV 수 × 1.5 + 보안등 수 × 1.0) / 경로 거리(km)

점수 = 10 + 90 × log(1 + 밀도 / k) / log(1 + 30 / k)
       (k = 3, 최대 100점)
```

> 로그 스케일을 사용하는 이유: CCTV가 0→1개 늘어날 때와 50→51개 늘어날 때의 안전 체감이 다르기 때문입니다.

## 6. 실행 화면

<img width="1834" height="825" alt="image" src="https://github.com/user-attachments/assets/accd5784-fa3b-4e2d-b448-45a5c11fdc59" />
<img width="1297" height="585" alt="image" src="https://github.com/user-attachments/assets/31a7d534-0c91-4e83-96b9-605ab53b89f1" />
<img width="1668" height="751" alt="image" src="https://github.com/user-attachments/assets/dda78801-1fd5-4b0b-bc89-8c1c885f3c60" />

## 7. 시연 영상

https://github.com/user-attachments/assets/a64070e7-1fe6-4a8c-a40c-38c915a418e5

## 8. 문제 해결 

* **보행자 경로 API 문제:** Naver Map API는 보행자 경로에서 경유지를 지원하지 않아 자동차 경로 기반으로만 안내되는 문제가 있었습니다. TMap 보행자 API를 도입하여 경로 계산은 TMap으로, 지도 렌더링은 Naver Map으로 분리하는 방식으로 해결했습니다.


<br/>
