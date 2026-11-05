"""Hevy и GymKeeper -> каталог для `gym import` (ADR 0013).

Результат — личные данные: --out указывайте вне репозитория.

    gym exercise list > $TMP/catalog.json
    python -I import/hevy_gymkeeper.py --catalog $TMP/catalog.json --out $TMP/2026-10 \
        --hevy <выгрузка Hevy> --routines <папка с *.hevy-routine-proposal.json> \
        --gymkeeper <выгрузка.csv> --hevy-measures <measurement_data.csv> --from 2026-10-09
    gym import $TMP/2026-10 --dry-run

--from отсекает тренировки раньше даты (пробные записи); замеры берутся все. План из
сохранённого шаблона Hevy берётся, только если в его день есть тренировка Hevy.
Рядом с каталогом пишется <каталог>.photos.json — фото тренировок для `gym photo add`.
"""
import argparse
import csv
import json
import shutil
import sys
import uuid
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

NS = uuid.UUID("fde5d64b-7ed4-44f6-82c5-8df8aea58b88")  # IMPORT_NAMESPACE в cli
TZ = timezone(timedelta(hours=4))  # Asia/Yerevan
OFFSET = "+04:00"
BODYWEIGHT = "weight"  # BODYWEIGHT в core


def iid(source, ref):
    return str(uuid.uuid5(NS, f"{source}:{ref}"))


# Шаблон Hevy -> nameRu справочника train; второе — переопределения подхода (perSide), если понадобятся
HEVY_MAP = {
    "AC1BB830": ("Бег", {}),
    "040BA2E3": ("Скакалка", {}),  # «секунды» в Hevy — на деле прыжки
    "1B2B1E7C": ("Подтягивания нейтральным хватом", {}),  # другим хватом владелец не подтягивается — локоть
    "392887AA": ("Отжимания", {}),
    "3D0C7C75": ("Гоблет-присед", {}),
    "D8911FC4": ("Мёртвый жук", {}),
    "218DA87C": ("Супермен", {}),
    "CBC0D834": ("Ягодичный мост на одной ноге", {}),
    "422B08F1": ("Махи гантелями в стороны", {}),
    "C6C9B8A0": ("Планка", {}),
    "2982AA23": ("Русские скручивания", {}),
    "756EE329": ("Жим гантели с пола одной рукой", {}),
    "F1E57334": ("Тяга гантели одной рукой с опорой", {}),
    "BD0AD077": ("Птица-собака", {}),
    "DC59D143": ("Отведение ноги в сторону стоя", {}),
    "7E3BC8B6": ("Сгибание рук «молот»", {}),
    "B5D3A742": ("Болгарские выпады с гантелями", {}),
    "9694DA61": ("Присед без веса", {}),
    "C43825EA": ("Отжимания с ногами на возвышении", {}),
    "CDA23948": ("Ягодичный мост", {}),
}

GK_MAP = {
    "Беговая дорожка": "Бег",
    "Скакалка": "Скакалка",
    "Птица-собака": "Птица-собака",
    "Приседания": "Присед без веса",
    "Приседания сумо · гантель": "Сумо-присед с гантелью",
    "Отжимания": "Отжимания",
    "Подтягивания обратным узким хватом": "Подтягивания нейтральным хватом",  # подтягивается только нейтральным
    "Планка": "Планка",
    "Болгарские приседания": "Болгарские выпады с гантелями",
    "Болгарские приседания · гантели": "Болгарские выпады с гантелями",
    "Отжимания с ногами на возвышении": "Отжимания с ногами на возвышении",
    "Тяга в наклоне одной рукой · гантель": "Тяга гантели одной рукой с опорой",
    "Боковая планка": "Боковая планка",
    "Ягодичный мостик · гантель": "Ягодичный мост",
    "Сгибание рук молотковым хватом · гантели": "Сгибание рук «молот»",
    "Обратные отжимания": "Обратные отжимания",
    "Подъем коленей в висе": "Подъём коленей в висе",
    "Мертвый жук": "Мёртвый жук",
    "Супермен": "Супермен",
}
GK_RATING = {"Легк": "EASY", "Норм": "NORMAL", "Тяжел": "HARD"}


class Catalog:
    def __init__(self, path):
        self.by_name = {e["nameRu"]: e for e in json.loads(Path(path).read_text(encoding="utf-8"))}
        self.by_id = {e["id"]: e for e in self.by_name.values()}
        self.missing = set()

    def get(self, name):
        e = self.by_name.get(name)
        if e is None:
            self.missing.add(name)
            return {"id": iid("missing", name), "recordType": "REPS", "perSide": False, "nameRu": name}
        return e


def iso(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_time(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def clean(d):
    return {k: v for k, v in d.items() if v is not None}


# ---------- значения подхода из Hevy ----------

def hevy_values(ex, s, timed_as_reps):
    """recordType и значения одного подхода Hevy для упражнения справочника ex."""
    rt = ex["recordType"]
    w, reps, sec = s.get("weight_kg"), s.get("reps"), s.get("duration_seconds")
    rng = s.get("rep_range") or {}
    out = {}
    if timed_as_reps:  # скакалка: секунды Hevy — прыжки
        return "REPS", {"reps": sec if sec is not None else reps}
    if rt in ("TIME", "WEIGHT_TIME"):
        out["seconds"] = sec
        if rt == "WEIGHT_TIME":
            out["weight"] = w
        return rt, out
    if rt == "WEIGHT_REPS" and not w:
        rt = "REPS"  # без веса — повторы
    if rt == "WEIGHT_REPS":
        out["weight"] = float(w)
    elif w:
        out["extraWeight"] = float(w)
    out["reps"] = reps
    if rng:
        out["repsMin"], out["repsMax"] = rng.get("start"), rng.get("end")
    return rt, out


def target(values):
    t = {}
    for k in ("weight", "extraWeight", "seconds"):
        if values.get(k) is not None:
            t[k] = values[k]
    if values.get("repsMin") is not None:
        t["repsMin"], t["repsMax"] = values["repsMin"], values.get("repsMax")
    elif values.get("reps") is not None:
        t["repsMin"] = t["repsMax"] = values["reps"]
    return t


# ---------- блоки: разминка, круги по superset, одиночные ----------

def group(exercises):
    """Hevy exercises -> [(key, role, title, [(exIndex, ex, [sets])])]. Разминочные подходы — в блок разминки."""
    warm = []
    blocks = []
    by_superset = {}
    for i, e in enumerate(exercises):
        ws = [s for s in e["sets"] if s["type"] == "warmup"]
        ns = [s for s in e["sets"] if s["type"] != "warmup"]
        if ws:
            warm.append((f"w{i}", e, ws))
        if not ns:
            continue
        sid = e.get("superset_id")
        if sid is None:
            blocks.append([f"s{i}", None, [(f"n{i}", e, ns)]])
        elif sid in by_superset:
            by_superset[sid][2].append((f"n{i}", e, ns))
        else:
            b = [f"c{sid}", sid, [(f"n{i}", e, ns)]]
            by_superset[sid] = b
            blocks.append(b)
    out = []
    if warm:
        out.append(("warmup", "WARMUP", "Разминка", warm))
    n = 0
    for key, sid, items in blocks:
        n += 1
        title = f"Круг {n}" if sid is not None else None
        out.append((key, "MAIN", title, items))
    return out


# ---------- планы из сохранённых шаблонов ----------

def plan_from_proposal(path, cat):
    p = json.loads(Path(path).read_text(encoding="utf-8"))
    r = p["routine"]
    pref = f"{p['hevy_routine_id']}@{p['target_date']}"
    blocks = []
    index = {}  # (template, n-е вхождение, warm|norm) -> item
    seen = {}
    for key, role, title, items in group(r["exercises"]):
        bid = iid("hevy-plan", f"{pref}/block/{key}")
        bitems = []
        for ikey, e, sets in items:
            tid = e["exercise_template_id"]
            name, over = HEVY_MAP[tid]
            ex = cat.get(name)
            timed = name == "Скакалка"
            rts, targets = [], []
            for si, s in enumerate(sets):
                if timed:  # в плане — настоящие минуты скакалки
                    rt, v = "TIME", {"seconds": s.get("duration_seconds")}
                else:
                    rt, v = hevy_values(ex, s, False)
                rts.append(rt)
                targets.append({"id": iid("hevy-plan", f"{pref}/target/{ikey}/{si}"), **target(v)})
            item = clean({
                "id": iid("hevy-plan", f"{pref}/item/{ikey}"),
                "exerciseId": ex["id"],
                "recordType": rts[0] if rts[0] != ex["recordType"] else None,
                "perSide": over.get("perSide"),
                "sets": targets,
                "restSec": e.get("rest_seconds"),
                "note": e.get("notes") or None,
            })
            bitems.append(item)
            kind = "warm" if ikey.startswith("w") else "norm"
            n = seen.get((tid, kind), 0)
            seen[(tid, kind)] = n + 1
            index[(tid, n, kind)] = (bid, item)
        blocks.append(clean({"id": bid, "role": role, "title": title, "items": bitems}))
    title = r["title"].removeprefix("🏠 ").strip()
    plan = clean({
        "id": iid("hevy-plan", pref),
        "date": p["target_date"],
        "title": title,
        "body": clean({"blocks": blocks, "note": r.get("notes") or None}),
    })
    return plan, index


# ---------- тренировки Hevy ----------

def spread(start, end, n):
    step = (end - start) / (n + 1)
    return [start + step * (i + 1) for i in range(n)]


def hevy_workout(w, cat, plan, index):
    wref = w["id"]
    start, end = parse_time(w["start_time"]), parse_time(w["end_time"])
    wid = iid("hevy", wref)
    # Раскладка: снимок плана, затем добавленное в тренировке
    layout = []
    item_of = {}  # id строки -> (block, item)
    if plan:
        for b in plan["body"]["blocks"]:
            lb = {"id": b["id"], "role": b["role"], "items": []}
            if b.get("title"):
                lb["title"] = b["title"]
            for it in b["items"]:
                ex = cat.by_id.get(it["exerciseId"], {"recordType": "REPS", "perSide": False})
                li = clean({
                    "id": it["id"], "exerciseId": it["exerciseId"],
                    "recordType": it.get("recordType") or ex["recordType"],
                    "perSide": it["perSide"] if "perSide" in it else ex.get("perSide", False),
                    "sets": it["sets"], "restSec": it.get("restSec"), "note": it.get("note"),
                })
                lb["items"].append(li)
                item_of[it["id"]] = (lb, li)
            layout.append(lb)
    added_block = None
    seen = {}
    facts = []
    pending = []
    for key, role, title, items in group(w["exercises"]):
        for ikey, e, sets in items:
            tid = e["exercise_template_id"]
            name, over = HEVY_MAP[tid]
            ex = cat.get(name)
            kind = "warm" if ikey.startswith("w") else "norm"
            n = seen.get((tid, kind), 0)
            seen[(tid, kind)] = n + 1
            match = index.get((tid, n, kind)) if plan else None
            pending.append((key, role, title, ikey, e, sets, tid, name, over, ex, match))
    used = {m[1]["id"] for *_, m in pending if m}
    for key, role, title, ikey, e, sets, tid, name, over, ex, match in pending:
            if match is None and plan and e.get("superset_id") is not None:
                # Замена в круге: напарник по кругу сопоставлен, а строка плана в его блоке без факта
                partner = next((m for (_, r2, _, _, e2, _, _, _, _, _, m) in pending
                                if m and e2.get("superset_id") == e["superset_id"] and r2 == role), None)
                if partner:
                    pb = item_of[partner[1]["id"]][0]
                    free = next((it for it in pb["items"] if it["id"] not in used), None)
                    if free:
                        used.add(free["id"])
                        free["replacedFrom"] = free["exerciseId"]
                        free["exerciseId"] = ex["id"]
                        free["perSide"] = over.get("perSide", ex.get("perSide", False))
                        match = (pb["id"], free)
            timed = name == "Скакалка"
            vals = [hevy_values(ex, s, timed) for s in sets]
            if match:
                lb, li = item_of[match[1]["id"]]
                li["recordType"] = vals[0][0]  # раскладка — как сделано
            else:
                lb = None
                if plan:
                    if added_block is None or (role == "WARMUP") != (added_block["role"] == "WARMUP"):
                        added_block = {"id": iid("hevy", f"{wref}/block/added-{key}"), "role": role, "title": "Добавлено", "items": []}
                        layout.append(added_block)
                    lb = added_block
                else:
                    bid = iid("hevy", f"{wref}/block/{key}")
                    lb = next((b for b in layout if b["id"] == bid), None)
                    if lb is None:
                        lb = clean({"id": bid, "role": role, "title": title, "items": []})
                        layout.append(lb)
                li = None
            if li is None:
                li = clean({
                    "id": iid("hevy", f"{wref}/item/{ikey}"), "exerciseId": ex["id"],
                    "recordType": vals[0][0],
                    "perSide": over.get("perSide", ex.get("perSide", False)),
                    "sets": [{"id": iid("hevy", f"{wref}/target/{ikey}/{i}"), **target(v)} for i, (rt, v) in enumerate(vals)],
                    "restSec": e.get("rest_seconds"),
                    "added": True if plan else None,
                })
                lb["items"].append(li)
            if e.get("notes"):
                li["comment"] = e["notes"]
            facts.append((lb, li, e, sets, vals, over, ex))
    # Отметки времени: в порядке выполнения — блоки по порядку, в круге по проходам
    order = []
    for lb in layout:
        rows = [f for f in facts if f[0] is lb]
        for p in range(max((len(f[3]) for f in rows), default=0)):
            for f in rows:
                if p < len(f[3]):
                    order.append((f, p))
    times = spread(start, end, len(order))
    sets_out = []
    for (f, p), t in zip(order, times):
        lb, li, e, sets, vals, over, ex = f
        rt, v = vals[p]
        s = sets[p]
        tgt = li["sets"][p]["id"] if p < len(li["sets"]) else None
        fact = clean({
            "id": iid("hevy", f"{wref}/set/{e['index']}/{s['index']}"),
            "workoutId": wid, "blockId": lb["id"], "itemId": li["id"], "setNo": p + 1,
            "targetId": tgt, "exerciseId": ex["id"], "recordType": rt,
            "perSide": over.get("perSide", ex.get("perSide", False)),
            "weight": v.get("weight"), "reps": v.get("reps"), "extraWeight": v.get("extraWeight"),
            "seconds": v.get("seconds"), "done": True, "doneAt": iso(t),
        })
        sets_out.append(fact)
    comment = "\n".join(x for x in [w.get("description") or None, f"Импорт из Hevy: {w['title']}, id {wref}"] if x)
    workout = clean({
        "id": wid, "planId": plan["id"] if plan else None, "startedAt": iso(start), "utcOffset": OFFSET,
        "finishedAt": iso(end), "comment": comment, "layout": {"blocks": layout},
    })
    return {"workout": workout, "sets": sets_out}


# ---------- GymKeeper ----------

def gk_day(d):
    return datetime.strptime(d, "%d.%m.%Y").date()


def noon(day):
    return datetime.combine(day, datetime.min.time()).replace(hour=12, tzinfo=TZ)


def gymkeeper_measures(rows):
    return [{"id": iid("gymkeeper", f"weight/{r['Date']}"), "type": "weight", "value": float(r["Val_1"]),
             "unit": "kg", "measuredAt": iso(noon(gk_day(r["Date"])))}
            for r in rows if r["Type"] == "📏" and r["Name"] == "Вес"]


RU_MONTHS = {"янв": 1, "февр": 2, "мар": 3, "апр": 4, "мая": 5, "май": 5, "июн": 6, "июл": 7, "авг": 8,
             "сент": 9, "окт": 10, "нояб": 11, "дек": 12}


def hevy_measure_day(s):
    """«5 окт. 2025, 00:00» — дата из выгрузки замеров Hevy с русской локалью."""
    d, m, y = s.split(",")[0].replace(".", "").split()
    return date(int(y), RU_MONTHS[m], int(d))


def hevy_measures(path):
    """measurement_data.csv из мобильного Hevy: строка — день, столбцы — weight_kg, fat_percent, *_cm.
    Время в выгрузке 00:00 — замер записывается полднем дня, как `measure add --at YYYY-MM-DD`."""
    out = []
    for r in csv.DictReader(open(path, encoding="utf-8-sig")):
        day = hevy_measure_day(r["date"])
        for col, v in r.items():
            if col == "date" or not v:
                continue
            if col == "weight_kg":
                kind, unit = BODYWEIGHT, "kg"
            elif col == "fat_percent":
                kind, unit = "body_fat", "%"
            else:
                kind, unit = col.removesuffix("_cm"), "cm"
            out.append({"id": iid("hevy-measure", f"{day.isoformat()}/{col}"), "type": kind, "value": float(v),
                        "unit": unit, "measuredAt": iso(noon(day))})
    return out


def gymkeeper(rows, day, cat, csv_name):
    src = "gymkeeper"
    ref = day.isoformat()
    rows_day = [r for r in rows if gk_day(r["Date"]) == day]
    minutes = next(int(r["Comment"].split()[0]) for r in rows_day if r["Type"] == "📅")
    # Времени начала в выгрузке нет: полдень по Еревану, как `--at YYYY-MM-DD`
    start = noon(day)
    end = start + timedelta(minutes=minutes)
    wid = iid(src, ref)
    exercises, skipped = [], []
    cur = None
    for r in rows_day:
        if r["Type"] == "🏋️‍♂️":
            cur = {"name": r["Name"], "sets": []}
            exercises.append(cur)
        elif r["Type"] == "🔹":
            cur["sets"].append(r)
    warm_items, main = [], []
    for i, e in enumerate(exercises):
        if not e["sets"]:
            skipped.append(e["name"])
            continue
        warm = all(s["Comment"].startswith("Размин") for s in e["sets"]) or e["name"] in ("Беговая дорожка", "Скакалка", "Птица-собака")
        (warm_items if warm else main).append((i, e))
    blocks = []
    if warm_items:
        blocks.append(("warmup", "WARMUP", "Разминка", warm_items))
    for i, e in main:
        blocks.append((f"s{i}", "MAIN", None, [(i, e)]))
    layout, facts = [], []
    for key, role, title, items in blocks:
        lb = clean({"id": iid(src, f"{ref}/block/{key}"), "role": role, "title": title, "items": []})
        for i, e in items:
            name = GK_MAP[e["name"]]
            ex = cat.get(name)
            per = ex.get("perSide", False)
            vals = []
            for s in e["sets"]:
                v1, u1, v2, u2 = s["Val_1"], s["Unit_1"], s["Val_2"], s["Unit_2"]
                rt = ex["recordType"]
                v = {}
                if name == "Скакалка":
                    rt, v = "REPS", {"reps": int(v2)}
                elif rt in ("TIME", "WEIGHT_TIME"):
                    v = {"seconds": int(v1)}
                else:
                    w = float(v1) if u1 == "кг" and float(v1) > 0 else None
                    if rt == "WEIGHT_REPS" and w is None:
                        rt = "REPS"
                    v = {"reps": int(v2)}
                    if w is not None:
                        v["weight" if rt == "WEIGHT_REPS" else "extraWeight"] = w
                c = s["Comment"].strip()
                word = c.split(" ")[0] if c else ""
                note = c[len(word):].strip().strip("()").strip() if word in GK_RATING or word.startswith("Размин") else c.strip("() ")
                vals.append((rt, v, GK_RATING.get(word), note or None))
            li = clean({
                "id": iid(src, f"{ref}/item/{i}"), "exerciseId": ex["id"], "recordType": vals[0][0], "perSide": per,
                "sets": [{"id": iid(src, f"{ref}/target/{i}/{k}"), **target(v)} for k, (rt, v, _, _) in enumerate(vals)],
                "comment": f"В GymKeeper: {e['name']}" if e["name"] != name else None,
            })
            lb["items"].append(li)
            facts.append((lb, li, i, vals, ex, per))
        layout.append(lb)
    order = []
    for lb in layout:
        for f in [f for f in facts if f[0] is lb]:
            for p in range(len(f[3])):
                order.append((f, p))
    sets_out = []
    for (f, p), t in zip(order, spread(start, end, len(order))):
        lb, li, i, vals, ex, per = f
        rt, v, rating, note = vals[p]
        sets_out.append(clean({
            "id": iid(src, f"{ref}/set/{i}/{p}"), "workoutId": wid, "blockId": lb["id"], "itemId": li["id"],
            "setNo": p + 1, "targetId": li["sets"][p]["id"], "exerciseId": ex["id"], "recordType": rt, "perSide": per,
            **v, "rating": rating, "note": note, "done": True, "doneAt": iso(t),
        }))
    comment = (f"Импорт из GymKeeper, выгрузка {csv_name}. Длительность по приложению {minutes} мин, "
               f"время начала неизвестно — записано полднем."
               + (f" Не сделаны: {', '.join(skipped)}." if skipped else ""))
    workout = {"id": wid, "startedAt": iso(start), "utcOffset": OFFSET, "finishedAt": iso(end),
               "comment": comment, "layout": {"blocks": layout}}
    return {"workout": workout, "sets": sets_out}


def write(path, obj):
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def local_day(w):
    return parse_time(w["start_time"]).astimezone(TZ).date()


def main():
    ap = argparse.ArgumentParser(description="Hevy и GymKeeper -> каталог для gym import")
    ap.add_argument("--catalog", required=True, help="вывод `gym exercise list`")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--hevy", type=Path, help="папка выгрузки Hevy: workouts.json, media.json, media/")
    ap.add_argument("--routines", type=Path, help="папка с *.hevy-routine-proposal.json")
    ap.add_argument("--gymkeeper", type=Path, action="append", default=[], help="CSV GymKeeper, можно несколько")
    ap.add_argument("--hevy-measures", type=Path, help="measurement_data.csv из мобильного Hevy")
    ap.add_argument("--from", dest="since", type=date.fromisoformat, help="тренировки с этого дня")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    cat = Catalog(a.catalog)

    def keep(day):
        return a.since is None or day >= a.since

    out = a.out
    if out.exists():
        shutil.rmtree(out)
    for sub in ("plans", "workouts", "measurements"):
        (out / sub).mkdir(parents=True)
    summary, photos, measures = [], [], []

    hevy = []
    if a.hevy:
        all_w = json.loads((a.hevy / "workouts.json").read_text(encoding="utf-8"))["workouts"]
        hevy = sorted((w for w in all_w if keep(local_day(w))), key=lambda w: w["start_time"])
    days = {local_day(w).isoformat() for w in hevy}
    plans = {}
    for f in sorted(a.routines.glob("*.hevy-routine-proposal.json")) if a.routines else []:
        target_date = json.loads(f.read_text(encoding="utf-8"))["target_date"]
        if target_date not in days:
            continue  # по шаблону не тренировались — это не история
        plan, index = plan_from_proposal(f, cat)
        plans[target_date] = (plan, index)
        write(out / "plans" / f"{target_date}.json", plan)
    for w in hevy:
        day = local_day(w).isoformat()
        plan, index = plans.get(day, (None, {}))
        detail = hevy_workout(w, cat, plan, index)
        write(out / "workouts" / f"{day}.hevy.json", detail)
        summary.append((day, "hevy", detail, plan))
    if a.hevy and (a.hevy / "media.json").exists():
        by_id = {w["id"]: w for w in hevy}
        for p in json.loads((a.hevy / "media.json").read_text(encoding="utf-8"))["photos"]:
            w = by_id.get(p["associated_workout_id"])
            if p["kind"] == "workout_photo" and w:
                # Даты съёмки Hevy не отдаёт: время — конец тренировки, к карточке которой фото
                photos.append({"file": str((a.hevy / p["file"]).resolve()), "id": iid("hevy-media", Path(p["file"]).name),
                               "at": iso(parse_time(w["end_time"])), "workoutId": iid("hevy", w["id"]),
                               "note": "из Hevy; время — конец тренировки, дата съёмки неизвестна"})

    for csv_path in a.gymkeeper:
        rows = list(csv.DictReader(open(csv_path, encoding="utf-8-sig")))
        measures += gymkeeper_measures(rows)
        for day in sorted({gk_day(r["Date"]) for r in rows if r["Type"] == "📅"}):
            if keep(day):
                detail = gymkeeper(rows, day, cat, csv_path.name)
                write(out / "workouts" / f"{day.isoformat()}.gymkeeper.json", detail)
                summary.append((day.isoformat(), "gymkeeper", detail, None))
    if measures:
        write(out / "measurements" / "gymkeeper.json", measures)
    if a.hevy_measures:
        hm = hevy_measures(a.hevy_measures)
        write(out / "measurements" / "hevy.json", hm)
        measures += hm
    photos_path = out.parent / f"{out.name}.photos.json"
    write(photos_path, photos)

    if cat.missing:
        print("нет в справочнике — добавьте `gym exercise add` и повторите:", ", ".join(sorted(cat.missing)))
    for day, src, d, plan in sorted(summary, key=lambda x: x[0]):
        print(day, src, d["workout"]["id"], "с планом" if plan else "без плана", len(d["sets"]), "подходов")
    print(len(measures), "замеров,", len(photos), "фото ->", photos_path)


if __name__ == "__main__":
    main()
