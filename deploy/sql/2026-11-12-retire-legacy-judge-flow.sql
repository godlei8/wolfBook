-- 下掉电子法官旧流程（旧版小程序建的房间，auto_judge_enabled = 0 / NULL）。
-- 代码里所有房间已经一律按新流程走；这里把还在打的旧流程房间记成流局，其余旧房间标成新流程（大厅里的旧房间开局后就是新流程）。
-- 可重复执行。2026-11-12 线上只有 2 个旧流程房间，都停在大厅。

UPDATE judge_rooms
SET room_status = 'finished',
    current_phase = 'result',
    winner_camp = NULL,
    latest_announcement = '旧版房间已停用，请重新建房',
    update_time = NOW()
WHERE room_status = 'playing'
  AND (auto_judge_enabled IS NULL OR auto_judge_enabled = 0);

UPDATE judge_rooms
SET auto_judge_enabled = 1
WHERE auto_judge_enabled IS NULL OR auto_judge_enabled = 0;
