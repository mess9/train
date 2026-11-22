-- Устройство, которое ведёт активную тренировку (ADR 0007, замок одной тренировки): ID установки
-- приложения и её вид (apk, web). У прежних тренировок и записанных CLI — null, замка нет
alter table workout add column device text;
alter table workout add column device_kind text;
