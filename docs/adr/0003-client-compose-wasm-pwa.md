# 0003. Клиент — PWA на Compose Multiplatform (Kotlin/Wasm)

Дата: 2026-11-01. Статус: принято, с проверкой рисков в начале работы.

## Решение
PWA на Compose Multiplatform, таргет Kotlin/Wasm. Всё на Kotlin, общий KMP-модуль `core` с сервером
и CLI.

## Почему
Владелец хочет один язык. PWA — без установки APK и с обновлением перезагрузкой. Compose выбран
вместо Compose HTML (не развивается) и kotlin-react: тот же UI-код можно собрать под Android, если
веб упрётся в ограничения — смена таргета, а не переписывание.

## Риски
Compose for Web — Beta, рендер в canvas. Первым делом проверить на Android Chrome: ввод чисел и
клавиатуру, wake lock, офлайн-запуск. Если плохо — Android-таргет (см. DESIGN.md, «Клиент»).
