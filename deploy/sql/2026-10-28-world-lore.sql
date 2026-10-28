-- 夜镇编年史：角色真名（alias）、称号、身世、人物关系，以及世界观（world_lore）。
-- 内容来自 docs/design/world-lore.md，全部原创。按角色名找 id，本地库和生产库都能直接跑；可以重复执行。
-- 依赖 ContentSchemaInitializer 已补上 roles.title / roles.relations 两列和 world_lore 表（新后端启动一次即可）。

SET @r0 := (SELECT id FROM roles WHERE name = '预言家');
SET @r1 := (SELECT id FROM roles WHERE name = '女巫');
SET @r2 := (SELECT id FROM roles WHERE name = '猎人');
SET @r3 := (SELECT id FROM roles WHERE name = '守卫');
SET @r4 := (SELECT id FROM roles WHERE name = '骑士');
SET @r5 := (SELECT id FROM roles WHERE name = '白痴');
SET @r6 := (SELECT id FROM roles WHERE name = '通灵师');
SET @r7 := (SELECT id FROM roles WHERE name = '织梦人');
SET @r8 := (SELECT id FROM roles WHERE name = '魔术师');
SET @r9 := (SELECT id FROM roles WHERE name = '摄梦人');
SET @r10 := (SELECT id FROM roles WHERE name = '守墓人');
SET @r11 := (SELECT id FROM roles WHERE name = '驯熊师');
SET @r12 := (SELECT id FROM roles WHERE name = '平民');
SET @r13 := (SELECT id FROM roles WHERE name = '老酒鬼');
SET @r14 := (SELECT id FROM roles WHERE name = '狼人');
SET @r15 := (SELECT id FROM roles WHERE name = '狼美人');
SET @r16 := (SELECT id FROM roles WHERE name = '白狼王');
SET @r17 := (SELECT id FROM roles WHERE name = '黑狼王');
SET @r18 := (SELECT id FROM roles WHERE name = '机械狼');
SET @r19 := (SELECT id FROM roles WHERE name = '石像鬼');
SET @r20 := (SELECT id FROM roles WHERE name = '诡术师');
SET @r21 := (SELECT id FROM roles WHERE name = '梦魇之影');
SET @r22 := (SELECT id FROM roles WHERE name = '爱神');

UPDATE roles SET alias = '奥菲莉亚·霍恩', title = '看星的人', background = '在星象台长大的孤女，被老天文学家霍恩收养，学会了从星光里读人心。老人临终前把一颗盛着星屑的水晶球交给她，说「银钟裂的那年，去灰铃镇」。她提前三个月就来了，在钟楼下开了一间小小的占星铺，替镇上人看婚事、看收成，也在夜里一个一个地看他们的真面目。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r6, 'label', '同行，彼此不服'), JSON_OBJECT('roleId', @r5, 'label', '总能说中她刚看到的事')) WHERE name = '预言家';
UPDATE roles SET alias = '薇洛·艾许', title = '林中药师', background = '住在黑松林边缘小屋里的药师，镇上人生病都去找她，病好了却从不请她吃饭。她的药柜最高一格只放两只瓶子：一瓶能把人从鬼门关拉回来，一瓶能把人送进去。母亲留给她的话只有一句：「两瓶都只能用一次，想清楚再打开。」', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r3, 'label', '天天来送花的人'), JSON_OBJECT('roleId', @r2, 'label', '买银粉的老主顾')) WHERE name = '女巫';
UPDATE roles SET alias = '加雷斯·布兰', title = '银弹老手', background = '二十年前的一个满月夜，他的妻子和女儿在林边被狼拖走。他追进黑松林三天三夜，回来时只剩一身伤和一把枪。从那以后他把所有积蓄都换成了银子弹。镇上孩子怕他，因为他从不笑；狼人更怕他，因为他倒下前一定会开最后一枪。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r17, 'label', '认定的仇人'), JSON_OBJECT('roleId', @r11, 'label', '一起打过猎的旧识')) WHERE name = '猎人';
UPDATE roles SET alias = '罗兰·维斯特', title = '守夜剑', background = '南方贵族家的三儿子，嫌家里规矩多，十八岁带着一把剑离家游历，替人护镖、赶强盗，一路走到灰铃镇。本来只打算歇一晚，结果在药铺门口多看了一眼，就再也没走。他每晚提着剑在一户人家门口守到天亮，却从不说守的是谁。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r1, 'label', '心上人'), JSON_OBJECT('roleId', @r4, 'label', '比武输给过他，不服')) WHERE name = '守卫';
UPDATE roles SET alias = '塞德里克·诺斯', title = '誓约骑士', background = '北境骑士团最年轻的正式骑士，被派来调查灰铃镇的失踪案。他把骑士守则背得一字不差，第一条是「绝不放过恶，绝不错伤善」。所以他白天的决斗只有一次机会：赢了，狼人当场伏诛；输了，他用自己的命谢罪。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r3, 'label', '不守规矩的游侠'), JSON_OBJECT('roleId', @r20, 'label', '追查了三个镇子的骗子')) WHERE name = '骑士';
UPDATE roles SET alias = '汤姆·芬奇', title = '钟楼的傻小子', background = '钟楼看守老芬奇的孙子，十岁那年偷偷爬上钟楼顶，摸了一下银钟，从此说话颠三倒四：大家笑他就哭，大家哭他就笑。镇上开会要把他赶出去的时候，他会掏出爷爷留下的守钟人徽章，大家就只好让他留下。没人知道他那天在钟里看见了什么。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r10, 'label', '爷爷的老朋友'), JSON_OBJECT('roleId', @r0, 'label', '会突然对她说预言')) WHERE name = '白痴';
UPDATE roles SET alias = '艾琳', title = '镜中人', background = '镇长家客厅里挂了两百年的一面银边古镜，看过太多人说谎，终于在某个夜里从镜框里走了出来。她不会撒谎，也看不得别人撒谎：把镜面朝向一个人，就能照出他真正的身份——不只是好人或狼，是他到底是谁。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r0, 'label', '「她只看得到黑白」'), JSON_OBJECT('roleId', @r20, 'label', '镜子照不出的人')) WHERE name = '通灵师';
UPDATE roles SET alias = '诺拉·韦伯', title = '钟楼织梦人', background = '十年前在雾夜里失踪的老守夜人诺曼的女儿。她腿脚不便，走不了夜路，就在钟楼顶的阁楼里学会了另一种守夜：纺锤一转，把两个人的梦用银线缝在一起，让他们同生共死、在梦里互通消息。她相信父亲还在某个梦里，只是她还没找到那根线。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r9, 'label', '在梦里找人的朋友'), JSON_OBJECT('roleId', @r21, 'label', '在梦里见过它的影子')) WHERE name = '织梦人';
UPDATE roles SET alias = '马库斯·瓦伦丁', title = '午夜魔术师', background = '巡回马戏团「午夜大篷车」的台柱，来灰铃镇演三场，结果第一场就有观众在后台失踪。他决定留下来，用自己最拿手的把戏对付狼人：每晚悄悄换掉两个人的门牌号，让狼的爪子落空。他嘴上说是为了票房，其实是为了那个失踪的观众。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r20, 'label', '走了相反路的同行'), JSON_OBJECT('roleId', @r22, 'label', '溜进马戏团的小观众')) WHERE name = '魔术师';
UPDATE roles SET alias = '艾薇·莫斯', title = '苦艾酒馆老板娘', background = '十年前的雾夜，她躲在酒馆地窖里，从地板缝里看着父母被拖进了黑暗。她活了下来，接手了酒馆，也学会了一件别人不会的事：把人带进梦游。被她带走的人那一夜不在床上，狼人扑空；可如果她出了事，梦游的人也醒不过来了。她店里有一杯冒青烟的「苦艾」，喝下去的人会梦见她那晚看见的一切。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r21, 'label', '宿敌'), JSON_OBJECT('roleId', @r13, 'label', '最老的老主顾'), JSON_OBJECT('roleId', @r7, 'label', '同路人')) WHERE name = '摄梦人';
UPDATE roles SET alias = '埃德加·霍洛', title = '守墓人', background = '教堂墓园的守墓人，埋过镇上三代人。狼人死后到了后半夜会现出原形，全镇只有他守在坟边看得见。白天议事厅需要他说真话，晚上镇民却绕着墓园走。他抽一只没点着的烟斗，因为老朋友芬奇说过，火光会把林子里的东西引过来。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r5, 'label', '老友的孙子'), JSON_OBJECT('roleId', @r17, 'label', '埋过它的两个兄弟')) WHERE name = '守墓人';
UPDATE roles SET alias = '卡佳·伯恩', title = '山路护卫', background = '北边山里来的商队护卫，身边总跟着一头叫「栗子」的大棕熊。山里人说熊能闻出狼的血味：每天早上，只要她身边坐着狼人，栗子就会冲着天空咆哮一声。她这次是被商队留下来过冬的，结果这个冬天比山里还冷。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r2, 'label', '一起打过猎的旧识'), JSON_OBJECT('roleId', @r13, 'label', '栗子唯一肯让他摸')) WHERE name = '驯熊师';
UPDATE roles SET alias = '本·贝克 & 梅·索恩', title = '提灯的两个孩子', background = '本是面包房老贝克的学徒，梅是裁缝铺的女儿，两人是隔壁长大的邻居。老贝克失踪后，大人们不敢出门，他俩就一人一盏灯，结伴在钟声前把整条街走一遍。他们没有任何本事，只有眼睛、嘴和手里那一票——这也是全镇大多数人拥有的全部。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r14, 'label', '身上有股湿毛味的绅士'), JSON_OBJECT('roleId', @r5, 'label', '从小一起疯跑的钟楼小子')) WHERE name = '平民';
UPDATE roles SET alias = '巴纳比·斯陶特', title = '醉铁匠', background = '三十年前「铁匠之夜」的主角，赤手打退过狼人，也被咬了一口。狼毒留在血里，老神父教他用烈酒压住满月夜的冲动，他就一喝喝了三十年。狼人半夜来咬他，闻到满嘴酒气都要多犹豫一回，所以他总能多扛一次。他说喝酒是为了全镇，酒馆里没人信，也没人拆穿。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r9, 'label', '看着她长大的老主顾'), JSON_OBJECT('roleId', @r17, 'label', '三十年前的仇')) WHERE name = '老酒鬼';
UPDATE roles SET alias = '卢西安·格雷', title = '灰衣绅士', background = '黑鬃氏的年轻成员，今年春天以「外地来的布商」身份住进了镇上的旅店。他礼貌、慷慨，记得每个人的名字，周日还去教堂。只有在满月夜，他会把领结系得特别紧——那是他提醒自己别笑得太早。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r17, 'label', '族长，他的叔父'), JSON_OBJECT('roleId', @r12, 'label', '裁缝家的女儿在留意他')) WHERE name = '狼人';
UPDATE roles SET alias = '塞拉菲娜·罗丝', title = '玫瑰夫人', background = '黑鬃氏的谋士，也是族里最危险的一位。她能化作一个人人都说「非常漂亮」、却没人说得清长相的女人。被她挽住手臂的人会一言不发地跟她走；她倒下的那一刻，那个人也会跟着倒下。她喜欢玫瑰，因为玫瑰也有刺，只是大家总先看到花。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r17, 'label', '效忠，但各有算盘'), JSON_OBJECT('roleId', @r20, 'label', '她的棋子')) WHERE name = '狼美人';
UPDATE roles SET alias = '弗罗斯特', title = '霜鬃', background = '满月夜里以雪白的狼形降生，生来就控制不住月圆时的怒火，七岁失手伤了同族，被逐出族群。十五年后他回到黑松林，带着完全掌控的力量和一群追随者，要和黑鬃争王位。他认为狼族不该吃人也不该躲人，而是该有自己的城。为了这个目标，他不在乎先把谁撕碎。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r17, 'label', '争位的宿敌'), JSON_OBJECT('roleId', @r19, 'label', '不怕他怒火的同类')) WHERE name = '白狼王';
UPDATE roles SET alias = '莫德雷德·黑鬃', title = '黑鬃之王', background = '月下之族的现任族长。三十年前他还是那头越界咬人的年轻狼，被一个铁匠打回了林子，从此认定银钟之约只是人类拴狗的链子。今年秋天银钟裂开，他第一个嗅到了机会：「失去的三百年，由我带大家拿回来。」被逼到绝路时，他会用最后一口气带走一个人。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r16, 'label', '争夺王位'), JSON_OBJECT('roleId', @r13, 'label', '三十年前的仇'), JSON_OBJECT('roleId', @r2, 'label', '二十年前的血债')) WHERE name = '黑狼王';
UPDATE roles SET alias = '伊莱亚斯·科格', title = '发条狼', background = '钟表匠科格捡来的弃婴，在齿轮堆里长大，成年后第一个满月夜才知道自己身上流着狼血。他不愿做狼，也回不去做人，就给自己打造了一副黄铜发条的身体，谁的本事都能装进去：神职倒下后，他能把对方的能力拆下来，装在自己身上。他说这是在找「属于自己的零件」。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r8, 'label', '拆过他的道具箱'), JSON_OBJECT('roleId', @r16, 'label', '向他伸过手')) WHERE name = '机械狼';
UPDATE roles SET alias = '格里姆', title = '尖塔守望者', background = '一百二十年前石匠雕在钟楼顶的滴水嘴兽，被托付「替镇子盯着林子」。它盯得太久，久到有了心。它想告诉镇民林子里有什么，却只换来石头和咒骂。最后是狼人爬上塔顶，问它看见了什么。从那以后它替狼人盯着镇子：它不和狼群一起行动，却能在夜里看清任何一个人的身份；狼群倒尽之后，它会亲自下塔。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r5, 'label', '摸过银钟的孩子'), JSON_OBJECT('roleId', @r16, 'label', '相互敬重的孤独者')) WHERE name = '石像鬼';
UPDATE roles SET alias = '杰斯特', title = '百面戏子', background = '巡回剧团里专门替主角挨打的替身演员，演了一辈子别人。剧团被狼群截杀的那晚，他跪着求饶，狼人发现他能变成任何人的样子，就留下了他。从此他替狼人顶身份、换脸、演好人，每到一个镇子都换一个名字，连自己本来叫什么都忘了。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r15, 'label', '给他新脸的人'), JSON_OBJECT('roleId', @r4, 'label', '追了他三个镇子'), JSON_OBJECT('roleId', @r6, 'label', '照不出他的镜子')) WHERE name = '诡术师';
UPDATE roles SET alias = '无名', title = '梦底来客', background = '它没有名字，因为它是从十年前那场雾夜里所有人的噩梦中长出来的。它住在梦境最深处，每晚第一个醒来，选一个人，用恐惧把他按在床上：那一夜，那个人的本事全都使不出来。它说话很有礼貌，像一位穿着燕尾服的客人，只是从不在镜子里留下影子。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r9, 'label', '宿敌'), JSON_OBJECT('roleId', @r7, 'label', '知道她在找谁')) WHERE name = '梦魇之影';
UPDATE roles SET alias = '皮普', title = '捣蛋的箭', background = '跟着马戏团大篷车溜进镇子的小家伙，背着一张比自己还高的弓。开局第一夜，他会用箭把两个人连成一对：从此他们同生共死。他不在乎谁是狼谁是人，只在乎这个故事够不够精彩。有人说他是神，有人说他只是一个太无聊的孩子。', relations = JSON_ARRAY(JSON_OBJECT('roleId', @r8, 'label', '混进马戏团时认识的大叔'), JSON_OBJECT('roleId', @r3, 'label', '最想射一箭的人')) WHERE name = '爱神';

INSERT INTO world_lore (id, content, update_time) VALUES (1, JSON_OBJECT('townName', '灰铃镇', 'tagline', '银钟响过，不出家门。', 'intro', '黑松林边上的一座山谷小镇，石板街、尖顶房、一座比全镇都老的钟楼。钟楼里挂着一口银钟，月亮升起时会自己响一声。
三百年来，镇上人都守着一条老规矩：银钟响过，不出家门。钟声之后的街道属于别的东西。久而久之，外乡人管这里叫「夜镇」。
今年秋天，银钟裂了一道缝。从那以后，每到夜里，镇上就有人不见。', 'coverImage', NULL, 'factions', JSON_ARRAY(JSON_OBJECT('name', '守钟人', 'camp', 'good', 'description', '灰铃镇的居民和守护者。大多数人只是面包师、裁缝、酒馆常客，少数人身怀异能：能看星的、会配药的、能走进梦里的。他们的武器是白天的议事厅：每天天亮，全镇聚在钟楼下，投票把可疑的人赶出镇子。'), JSON_OBJECT('name', '月下之族', 'camp', 'wolf', 'description', '三百年前就住在黑松林里的狼人家族，能变成人形混在镇民中间过日子。族里分两支：以黑鬃为首、想让人类俯首的「黑鬃氏」，和追随一头白狼、认为狼族该另寻出路的「白霜氏」。'), JSON_OBJECT('name', '局外人', 'camp', 'third', 'description', '不站在任何一边的存在。他们来夜镇不是为了救谁或吃谁，而是为了自己的故事。')), 'events', JSON_ARRAY(JSON_OBJECT('era', '三百年前', 'title', '银钟之约', 'content', '第一代镇民在黑松林边开荒，与月下之族的老族长在满月下立约：钟声之前是人的时间，钟声之后是狼的时间，谁也不越界。铸钟的银子里，掺了双方的一滴血。', 'roleIds', JSON_ARRAY()), JSON_OBJECT('era', '一百二十年前', 'title', '尖塔上的守望者', 'content', '修钟楼的石匠在塔顶雕了一只滴水嘴兽，用来「替镇子盯着林子」。没人想到它真的在盯。', 'roleIds', JSON_ARRAY(@r19)), JSON_OBJECT('era', '三十年前', 'title', '铁匠之夜', 'content', '一头年轻的狼越界咬人，铁匠巴纳比赤手空拳把它打回了林子，自己也被咬了一口。镇上从此知道：约定是会被打破的。', 'roleIds', JSON_ARRAY(@r13, @r17)), JSON_OBJECT('era', '二十年前', 'title', '林边的满月', 'content', '猎户布兰的妻子和女儿在林边被狼拖走。他追进黑松林三天三夜，回来时只剩一把枪，从此只用银子弹。', 'roleIds', JSON_ARRAY(@r2, @r17)), JSON_OBJECT('era', '十年前', 'title', '雾夜', 'content', '老守夜人诺曼巡街时走进一场大雾，再没回来。同一夜，酒馆老板夫妇失踪，他们的女儿躲在地窖里活了下来。那一夜所有人的噩梦，长出了一个没有名字的东西。', 'roleIds', JSON_ARRAY(@r7, @r9, @r21)), JSON_OBJECT('era', '今年夏末', 'title', '白狼归来', 'content', '被逐出族群十五年的白狼弗罗斯特回到黑松林，带回了一批追随者，狼族内部开始争夺王位。', 'roleIds', JSON_ARRAY(@r16, @r17)), JSON_OBJECT('era', '今年秋天', 'title', '银钟裂了', 'content', '没人知道是谁做的。当晚，钟声没有响完。第二天，面包房的老贝克没来开门。——每一局游戏，都从这里开始。', 'roleIds', JSON_ARRAY(@r12, @r5, @r0)))), NOW())
  ON DUPLICATE KEY UPDATE content = VALUES(content), update_time = NOW();
