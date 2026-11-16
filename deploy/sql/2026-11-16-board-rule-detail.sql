-- 由 scripts/judge-rules-sql.py 从 docs/judge/house-rules/ 生成，可重复执行。
-- 把玩法概述、角色细则写进板子的规则详情；夜间顺序、规则章节和电子法官规则不动；清掉旧的 special_rules。
-- 跑完在后台保存一次板子，让 AI 助手资料重建。

UPDATE boards SET
  rule_detail = JSON_SET(COALESCE(rule_detail, JSON_OBJECT()),
    '$.overview', '12 人标准局：四狼、四神、四民，没有特殊狼。好人靠预言家的查验和女巫的两瓶药找狼，猎人出局时能带走一人，白痴被放逐时翻牌亮明身份。',
    '$.roleRules', JSON_ARRAY(
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '平民' LIMIT 1), 'items', JSON_ARRAY('没有技能，每天参加放逐投票。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '预言家' LIMIT 1), 'items', JSON_ARRAY('每晚验一人，只报好人或狼人。狼王、石像鬼这些狼人阵营的角色都报狼人。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '女巫' LIMIT 1), 'items', JSON_ARRAY('一瓶解药、一瓶毒药，一晚只能用一瓶。', '全程不可自救：被刀的是自己时不能用解药。', '只在还有解药时告知刀口，解药用完后不再告知；毒药照常能用。', '解药只救被狼人刀的人。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '猎人' LIMIT 1), 'items', JSON_ARRAY('开枪：被刀、被放逐、被开枪带走都能开枪；被毒（女巫或学到女巫的机械狼人）、被法官点杀不能。', '可以带走任何一名活着的玩家，不能带走自己。开枪不翻牌，公告「X 号发动技能，带走了 Y 号」。', '夜里有 10 秒一步，告知能不能开枪。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '白痴' LIMIT 1), 'items', JSON_ARRAY('被放逐照样出局，出局时翻牌公开身份。', '被刀、被毒、被开枪带走和普通出局一样。', '是警长时照常移交或撕掉警徽。', '夜里有 5 秒空步。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '狼人' LIMIT 1), 'items', JSON_ARRAY('狼人之间互相认识（狼王、狼美人也和狼人互相认识；机械狼人、石像鬼不认识）。', '每晚和狼队一起睁眼刀一人，可以空刀、可以自刀。意见不一按多数定刀口，票数一样多就在并列的人里随机。', '可以自爆：警长竞选投票前或放逐投票开始前都行，自爆后当场出局，有 60 秒遗言，跳过白天剩下的环节直接天黑。'))
    )
  ),
  special_rules = JSON_ARRAY()
WHERE name = '预女猎白';

UPDATE boards SET
  rule_detail = JSON_SET(COALESCE(rule_detail, JSON_OBJECT()),
    '$.overview', '12 人局。狼队里有一只狼王，出局时能开枪带走一人；好人多一个守卫，每晚守一个人，挡得住狼刀、挡不住毒。',
    '$.roleRules', JSON_ARRAY(
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '平民' LIMIT 1), 'items', JSON_ARRAY('没有技能，每天参加放逐投票。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '预言家' LIMIT 1), 'items', JSON_ARRAY('每晚验一人，只报好人或狼人。狼王、石像鬼这些狼人阵营的角色都报狼人。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '女巫' LIMIT 1), 'items', JSON_ARRAY('一瓶解药、一瓶毒药，一晚只能用一瓶。', '全程不可自救：被刀的是自己时不能用解药。', '只在还有解药时告知刀口，解药用完后不再告知；毒药照常能用。', '解药只救被狼人刀的人。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '猎人' LIMIT 1), 'items', JSON_ARRAY('开枪：被刀、被放逐、被开枪带走都能开枪；被毒（女巫或学到女巫的机械狼人）、被法官点杀不能。', '可以带走任何一名活着的玩家，不能带走自己。开枪不翻牌，公告「X 号发动技能，带走了 Y 号」。', '夜里有 10 秒一步，告知能不能开枪。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '守卫' LIMIT 1), 'items', JSON_ARRAY('每晚守一人，可以自守、可以空守，不能连续两晚守同一人。', '只挡狼刀，挡不住毒（女巫的毒、机械狼人的毒都挡不住）。', '同守同救：被守的人又被女巫用了解药，照死。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '狼人' LIMIT 1), 'items', JSON_ARRAY('狼人之间互相认识（狼王、狼美人也和狼人互相认识；机械狼人、石像鬼不认识）。', '每晚和狼队一起睁眼刀一人，可以空刀、可以自刀。意见不一按多数定刀口，票数一样多就在并列的人里随机。', '可以自爆：警长竞选投票前或放逐投票开始前都行，自爆后当场出局，有 60 秒遗言，跳过白天剩下的环节直接天黑。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '黑狼王' LIMIT 1), 'items', JSON_ARRAY('和狼人互相认识，每晚一起刀人，可以自刀。', '开枪：被刀、被放逐、被猎人带走都能开枪；被毒、被法官点杀、自爆、是最后一只狼时不能。开枪不翻牌，公告和猎人同一句。', '可以自爆，自爆后不能开枪。', '预言家验狼王报狼人。'))
    )
  ),
  special_rules = JSON_ARRAY()
WHERE name = '狼王守卫';

UPDATE boards SET
  rule_detail = JSON_SET(COALESCE(rule_detail, JSON_OBJECT()),
    '$.overview', '好人的查验位换成通灵师，验出来的是具体身份。狼人阵营多一只和狼队互不认识的机械狼人：整局能学一次别人的技能，狼人全部死亡后由他接过刀。',
    '$.roleRules', JSON_ARRAY(
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '平民' LIMIT 1), 'items', JSON_ARRAY('没有技能，每天参加放逐投票。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '通灵师' LIMIT 1), 'items', JSON_ARRAY('每晚验一人，报具体身份（角色名）。', '验机械狼人：他没学过报机械狼人，学过报他学到的身份。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '女巫' LIMIT 1), 'items', JSON_ARRAY('一瓶解药、一瓶毒药，一晚只能用一瓶。', '全程不可自救：被刀的是自己时不能用解药。', '只在还有解药时告知刀口，解药用完后不再告知；毒药照常能用。', '解药只救被狼人刀的人。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '猎人' LIMIT 1), 'items', JSON_ARRAY('开枪：被刀、被放逐、被开枪带走都能开枪；被毒（女巫或学到女巫的机械狼人）、被法官点杀不能。', '可以带走任何一名活着的玩家，不能带走自己。开枪不翻牌，公告「X 号发动技能，带走了 Y 号」。', '夜里有 10 秒一步，告知能不能开枪。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '守卫' LIMIT 1), 'items', JSON_ARRAY('每晚守一人，可以自守、可以空守，不能连续两晚守同一人。', '只挡狼刀，挡不住毒（女巫的毒、机械狼人的毒都挡不住）。', '同守同救：被守的人又被女巫用了解药，照死。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '狼人' LIMIT 1), 'items', JSON_ARRAY('狼人之间互相认识（狼王、狼美人也和狼人互相认识；机械狼人、石像鬼不认识）。', '每晚和狼队一起睁眼刀一人，可以空刀、可以自刀。意见不一按多数定刀口，票数一样多就在并列的人里随机。', '可以自爆：警长竞选投票前或放逐投票开始前都行，自爆后当场出局，有 60 秒遗言，跳过白天剩下的环节直接天黑。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '机械狼' LIMIT 1), 'items', JSON_ARRAY('和狼人互不认识。每晚最先单独睁眼。不能自爆。', '学习：整局一次，任意一晚选一名玩家，学到对方身份的技能。学到的技能下一晚起能用；学到猎人，学习当晚起出局就能开枪。', '带刀：狼人在时不带刀；狼人全部死亡后带刀，每晚一刀普通刀。第二晚起每晚睁眼先告知是否带刀。', '在哪一步用：普通刀口在狼人那一步选，和狼人一样确定 / 重新选择；学到的技能（查验、毒、守护、破盾刀）都在机械狼人那一步用。', '被通灵师验：没学过报机械狼人，学过报学到的身份。', '学到通灵师：每晚验一人的具体身份。', '学到女巫：固定一瓶毒药，没有解药。女巫看不到它的毒口、救不了；守卫挡不住；猎人被它毒不能开枪。', '学到猎人：开枪规则同猎人。', '学到守卫：每晚守一人，可以自守、空守，不能连守；挡狼刀，同守同救照死。和守卫守同一人，两个守护都生效。还能挡毒，见下面「弹毒」。', '学到狼人：带刀后多一刀破盾刀。两刀可以只出一刀，也可以打同一人。破盾刀无视所有守护，女巫看不到它的目标；两刀打同一人时女巫用解药，解药耗掉、人照死。', '学到平民，或者一直没学：没有额外技能。', '弹毒不开：被学到守卫的机械狼人守护的人中毒不死。', '弹毒开：毒反弹给下毒的人，被守的人不死，下毒的人被毒死。弹回去的毒守卫挡不住；女巫被弹毒而死算被毒杀。'))
    )
  ),
  special_rules = JSON_ARRAY()
WHERE name = '机械狼通灵师';

UPDATE boards SET
  rule_detail = JSON_SET(COALESCE(rule_detail, JSON_OBJECT()),
    '$.overview', '狼队里有狼美人，每晚魅惑一人，她死的时候被魅惑的人跟着殉情。好人有骑士，白天可以决斗一人：对方是狼人则狼人死，是好人则骑士死；另有守卫。',
    '$.roleRules', JSON_ARRAY(
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '平民' LIMIT 1), 'items', JSON_ARRAY('没有技能，每天参加放逐投票。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '预言家' LIMIT 1), 'items', JSON_ARRAY('每晚验一人，只报好人或狼人。狼王、石像鬼这些狼人阵营的角色都报狼人。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '女巫' LIMIT 1), 'items', JSON_ARRAY('一瓶解药、一瓶毒药，一晚只能用一瓶。', '全程不可自救：被刀的是自己时不能用解药。', '只在还有解药时告知刀口，解药用完后不再告知；毒药照常能用。', '解药只救被狼人刀的人。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '骑士' LIMIT 1), 'items', JSON_ARRAY('整局一次，白天发言时（放逐投票前）可以决斗一名玩家；警长竞选环节不能决斗。', '对方是狼人：该狼人死亡，直接天黑。', '对方是好人：骑士死亡，白天流程继续。', '全场只公布对方是狼人还是好人，不翻牌。', '夜里有 5 秒空步。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '守卫' LIMIT 1), 'items', JSON_ARRAY('每晚守一人，可以自守、可以空守，不能连续两晚守同一人。', '只挡狼刀，挡不住毒（女巫的毒、机械狼人的毒都挡不住）。', '同守同救：被守的人又被女巫用了解药，照死。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '狼人' LIMIT 1), 'items', JSON_ARRAY('狼人之间互相认识（狼王、狼美人也和狼人互相认识；机械狼人、石像鬼不认识）。', '每晚和狼队一起睁眼刀一人，可以空刀、可以自刀。意见不一按多数定刀口，票数一样多就在并列的人里随机。', '可以自爆：警长竞选投票前或放逐投票开始前都行，自爆后当场出局，有 60 秒遗言，跳过白天剩下的环节直接天黑。')),
      JSON_OBJECT('roleId', (SELECT id FROM roles WHERE name = '狼美人' LIMIT 1), 'items', JSON_ARRAY('和狼人互相认识。每晚和狼人一起刀人，之后单独睁眼魅惑一人，被魅惑的人不知情。', '魅惑除自己外谁都可以（包括狼队友），不能连续两晚魅惑同一人。', '狼美人当晚或当日死亡，被魅惑的人随之殉情；被骑士决斗死的不殉情。殉情的猎人不能开枪。', '殉情和狼美人算同一次出局：她是最后一只狼、殉情的是最后一个神职或平民时，狼人胜。', '不能自刀，不能自爆。', '守卫的守护不影响魅惑。'))
    )
  ),
  special_rules = JSON_ARRAY()
WHERE name = '狼美人骑士';
