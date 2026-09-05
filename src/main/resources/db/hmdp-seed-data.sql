-- ============================================================
-- hmdp-pro 种子数据（配合 hmdp-schema.sql 使用）
--
-- 设计说明：
-- 1. 秒杀券时间窗口全部基于 NOW() 计算，任何时间导入都成立：
--    - 券 10：进行中（进行中秒杀，可直接压测）
--    - 券 11：未开始（用于测试未开抢拦截）
--    - 券 12：已结束（用于测试对账/历史订单查询）
-- 2. 账本自洽（对账任务不误报）：
--    tb_seckill_voucher.stock = initial_stock - 已领取数（COUNT(*) 不筛 used，核销与否都占库存）
--    券 10：200 - 3 = 197；券 12：100 - 4 = 96；券 11：100 - 0 = 100
-- 3. tb_voucher_order 主键为 UidGenerator 生成的大整数，非自增；
--    同一秒杀洪峰内的订单号低位连续，符合 RingBuffer 预分配特征。
-- 4. tb_user 密码为空字符串：本项目登录走短信验证码模拟，密码字段不参与校验。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- 1. 用户（1~4 号用户贯穿所有数据）
-- ----------------------------
INSERT INTO `tb_user` (`id`, `phone`, `password`, `nick_name`, `icon`, `create_time`, `update_time`) VALUES
(1, '13800000001', '', '小鱼同学', '/imgs/icons/kkjtbcr.jpg', '2026-07-01 10:00:00', '2026-07-01 10:00:00'),
(2, '13800000002', '', '可可今天不吃肉', '/imgs/icons/kkjtbcr.jpg', '2026-07-03 15:20:00', '2026-07-03 15:20:00'),
(3, '13800000003', '', '阿澄爱喝汽水', '', '2026-07-10 09:30:00', '2026-07-10 09:30:00'),
(4, '13800000004', '', '柚子不去皮', '', '2026-07-18 21:00:00', '2026-07-18 21:00:00');

-- ----------------------------
-- 2. 用户信息（fans/followee 与 tb_follow 一致）
-- ----------------------------
INSERT INTO `tb_user_info` (`user_id`, `city`, `introduce`, `fans`, `followee`, `gender`, `birthday`, `credits`, `level`, `create_time`, `update_time`) VALUES
(1, '杭州', '爱探店的美食物种草机，更新看心情', 3, 2, 1, '2002-06-15', 320, 3, '2026-07-01 10:00:00', '2026-08-30 20:00:00'),
(2, '杭州', '甜品脑袋，减脂是不可能减脂的', 1, 1, 1, '2001-11-02', 150, 2, '2026-07-03 15:20:00', '2026-08-28 12:00:00'),
(3, '宣城', '周末就往杭州跑的赶路人', 0, 1, 0, '2003-03-22', 80, 1, '2026-07-10 09:30:00', '2026-09-01 08:00:00'),
(4, '杭州', '麦霸本霸，欢迎约歌', 1, 1, 0, '2002-09-09', 60, 1, '2026-07-18 21:00:00', '2026-09-01 22:30:00');

-- ----------------------------
-- 3. 关注关系（用户 1 是"共同关注"的中心节点：2/3/4 都关注了 1）
-- ----------------------------
INSERT INTO `tb_follow` (`user_id`, `follow_user_id`, `create_time`) VALUES
(1, 2, '2026-07-03 15:30:00'),
(1, 4, '2026-07-18 21:05:00'),
(2, 1, '2026-07-04 09:00:00'),
(3, 1, '2026-07-11 10:10:00'),
(4, 1, '2026-07-19 08:45:00');

-- ----------------------------
-- 4. 商铺类型（10 类）
-- ----------------------------
INSERT INTO `tb_shop_type` (`id`, `name`, `icon`, `sort`, `create_time`, `update_time`) VALUES
(1, '美食', '/types/ms.png', 1, '2026-06-01 20:17:47', '2026-06-01 20:17:47'),
(2, 'KTV', '/types/KTV.png', 2, '2026-06-01 20:18:27', '2026-06-01 20:18:27'),
(3, '丽人·美发', '/types/lrmf.png', 3, '2026-06-01 20:18:48', '2026-06-01 20:18:48'),
(4, '美睫·美甲', '/types/mjmj.png', 4, '2026-06-01 20:21:46', '2026-06-01 20:21:46'),
(5, '按摩·足疗', '/types/amzl.png', 5, '2026-06-01 20:19:27', '2026-06-01 20:19:27'),
(6, '美容SPA', '/types/spa.png', 6, '2026-06-01 20:19:35', '2026-06-01 20:19:35'),
(7, '亲子游乐', '/types/qzyl.png', 7, '2026-06-01 20:19:53', '2026-06-01 20:19:53'),
(8, '酒吧', '/types/jiuba.png', 8, '2026-06-01 20:20:02', '2026-06-01 20:20:02'),
(9, '轰趴馆', '/types/hpg.png', 9, '2026-06-01 20:20:08', '2026-06-01 20:20:08'),
(10, '健身运动', '/types/jsyd.png', 10, '2026-06-01 20:19:04', '2026-06-01 20:19:04');

-- ----------------------------
-- 5. 商铺（杭州拱墅区 14 家，坐标为真实位置，供附近商铺 GEO 检索使用）
-- ----------------------------
INSERT INTO `tb_shop` VALUES (1, '103茶餐厅', 1, 'https://qcloud.dpfile.com/pc/jiclIsCKmOI2arxKN1Uf0Hx3PucIJH8q0QSz-Z8llzcN56-_QiKuOvyio1OOxsRtFoXqu0G3iT2T27qat3WhLVEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vfCF2ubeXzk49OsGrXt_KYDCngOyCwZK-s3fqawWswzk.jpg,https://qcloud.dpfile.com/pc/IOf6VX3qaBgFXFVgp75w-KKJmWZjFc8GXDU8g9bQC6YGCpAmG00QbfT4vCCBj7njuzFvxlbkWx5uwqY2qcjixFEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vmIU_8ZGOT1OjpJmLxG6urQ.jpg', '大关', '金华路锦昌文华苑29号', 120.149192, 30.316078, 80, 4215, 3035, 37, '10:00-22:00', '2021-12-22 18:10:39', '2022-01-13 17:32:19');
INSERT INTO `tb_shop` VALUES (2, '蔡馬洪涛烤肉·老北京铜锅涮羊肉', 1, 'https://p0.meituan.net/bbia/c1870d570e73accbc9fee90b48faca41195272.jpg,http://p0.meituan.net/mogu/397e40c28fc87715b3d5435710a9f88d706914.jpg,https://qcloud.dpfile.com/pc/MZTdRDqCZdbPDUO0Hk6lZENRKzpKRF7kavrkEI99OxqBZTzPfIxa5E33gBfGouhFuzFvxlbkWx5uwqY2qcjixFEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vmIU_8ZGOT1OjpJmLxG6urQ.jpg', '拱宸桥/上塘', '上塘路1035号（中国工商银行旁）', 120.151505, 30.333422, 85, 2160, 1460, 46, '11:30-03:00', '2021-12-22 19:00:13', '2022-01-11 16:12:26');
INSERT INTO `tb_shop` VALUES (3, '新白鹿餐厅(运河上街店)', 1, 'https://p0.meituan.net/biztone/694233_1619500156517.jpeg,https://img.meituan.net/msmerchant/876ca8983f7395556eda9ceb064e6bc51840883.png,https://img.meituan.net/msmerchant/86a76ed53c28eff709a36099aefe28b51554088.png', '运河上街', '台州路2号运河上街购物中心F5', 120.151954, 30.32497, 61, 12035, 8045, 47, '10:30-21:00', '2021-12-22 19:10:05', '2022-01-11 16:12:42');
INSERT INTO `tb_shop` VALUES (4, 'Mamala(杭州远洋乐堤港店)', 1, 'https://img.meituan.net/msmerchant/232f8fdf09050838bd33fb24e79f30f9606056.jpg,https://qcloud.dpfile.com/pc/rDe48Xe15nQOHCcEEkmKUp5wEKWbimt-HDeqYRWsYJseXNncvMiXbuED7x1tXqN4uzFvxlbkWx5uwqY2qcjixFEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vmIU_8ZGOT1OjpJmLxG6urQ.jpg', '拱宸桥/上塘', '丽水路66号远洋乐堤港商城2期1层B115号', 120.146659, 30.312742, 290, 13519, 9529, 49, '11:00-22:00', '2021-12-22 19:17:15', '2022-01-11 16:12:51');
INSERT INTO `tb_shop` VALUES (5, '海底捞火锅(水晶城购物中心店）', 1, 'https://img.meituan.net/msmerchant/054b5de0ba0b50c18a620cc37482129a45739.jpg,https://img.meituan.net/msmerchant/59b7eff9b60908d52bd4aea9ff356e6d145920.jpg,https://qcloud.dpfile.com/pc/Qe2PTEuvtJ5skpUXKKoW9OQ20qc7nIpHYEqJGBStJx0mpoyeBPQOJE4vOdYZwm9AuzFvxlbkWx5uwqY2qcjixFEuLYk00OmSS1IdNpm8K8sG4JN9RIm2mTKcbLtc2o2vmIU_8ZGOT1OjpJmLxG6urQ.jpg', '大关', '上塘路458号水晶城购物中心F6', 120.15778, 30.310633, 104, 4125, 2764, 49, '10:00-07:00', '2021-12-22 19:20:58', '2022-01-11 16:13:01');
INSERT INTO `tb_shop` VALUES (6, '幸福里老北京涮锅（丝联店）', 1, 'https://img.meituan.net/msmerchant/e71a2d0d693b3033c15522c43e03f09198239.jpg,https://img.meituan.net/msmerchant/9f8a966d60ffba00daf35458522273ca658239.jpg,https://img.meituan.net/msmerchant/ef9ca5ef6c05d381946fe4a9aa7d9808554502.jpg', '拱宸桥/上塘', '金华南路189号丝联166号', 120.148603, 30.318618, 130, 9531, 7324, 46, '11:00-13:50,17:00-20:50', '2021-12-22 19:24:53', '2022-01-11 16:13:09');
INSERT INTO `tb_shop` VALUES (7, '炉鱼(拱墅万达广场店)', 1, 'https://img.meituan.net/msmerchant/909434939a49b36f340523232924402166854.jpg,https://img.meituan.net/msmerchant/32fd2425f12e27db0160e837461c10303700032.jpg,https://img.meituan.net/msmerchant/f7022258ccb8dabef62a0514d3129562871160.jpg', '北部新城', '杭行路666号万达商业中心4幢2单元409室(铺位号4005)', 120.124691, 30.336819, 85, 2631, 1320, 47, '00:00-24:00', '2021-12-22 19:40:52', '2022-01-11 16:13:19');
INSERT INTO `tb_shop` VALUES (8, '浅草屋寿司（运河上街店）', 1, 'https://img.meituan.net/msmerchant/cf3dff697bf7f6e11f4b79c4e7d989e4591290.jpg,https://img.meituan.net/msmerchant/0b463f545355c8d8f021eb2987dcd0c8567811.jpg,https://img.meituan.net/msmerchant/c3c2516939efaf36c4ccc64b0e629fad587907.jpg', '运河上街', '拱墅区金华路80号运河上街B1', 120.150526, 30.325231, 88, 2406, 1206, 46, ' 11:00-21:30', '2021-12-22 19:51:06', '2022-01-11 16:13:25');
INSERT INTO `tb_shop` VALUES (9, '羊老三羊蝎子牛仔排北派炭火锅(运河上街店)', 1, 'https://p0.meituan.net/biztone/163160492_1624251899456.jpeg,https://img.meituan.net/msmerchant/e478eb16f7e31a7f8b29b5e3bab6de205500837.jpg,https://img.meituan.net/msmerchant/6173eb1d18b9d70ace7fdb3f2dd939662884857.jpg', '运河上街', '台州路2号运河上街购物中心F5', 120.150598, 30.325251, 101, 2763, 1363, 44, '11:00-21:30', '2021-12-22 19:53:59', '2022-01-11 16:13:34');
INSERT INTO `tb_shop` VALUES (10, '开乐迪KTV（运河上街店）', 2, 'https://p0.meituan.net/joymerchant/a575fd4adb0b9099c5c410058148b307-674435191.jpg,https://p0.meituan.net/merchantpic/68f11bf850e25e437c5f67decfd694ab2541634.jpg,https://p0.meituan.net/dpdeal/cb3a12225860ba2875e4ea26c6d14fcc197016.jpg', '运河上街', '台州路2号运河上街购物中心F4', 120.149093, 30.324666, 67, 26891, 902, 37, '00:00-24:00', '2021-12-22 20:25:16', '2021-12-22 20:25:16');
INSERT INTO `tb_shop` VALUES (11, 'INLOVE KTV(水晶城店)', 2, 'https://p0.meituan.net/dpmerchantpic/53e74b200211d68988a4f02ae9912c6c1076826.jpg,https://qcloud.dpfile.com/pc/4iWtIvzLzwM2MGgyPu1PCDb4SWEaKqUeHm--YAt1EwR5tn8kypBcqNwHnjg96EvT_Gd2X_f-v9T8Yj4uLt25Gg.jpg,https://qcloud.dpfile.com/pc/WZsJWRI447x1VG2x48Ujgu7vwqksi_9WitdKI4j3jvIgX4MZOpGNaFtM93oSSizbGybIjx5eX6WNgCPvcASYAw.jpg', '水晶城', '上塘路458号水晶城购物中心6层', 120.15853, 30.310002, 75, 35977, 5684, 47, '11:30-06:00', '2021-12-22 20:29:02', '2021-12-22 20:39:00');
INSERT INTO `tb_shop` VALUES (12, '魅(杭州远洋乐堤港店)', 2, 'https://p0.meituan.net/dpmerchantpic/63833f6ba0393e2e8722420ef33f3d40466664.jpg,https://p0.meituan.net/dpmerchantpic/ae3c94cc92c529c4b1d7f68cebed33fa105810.png,', '远洋乐堤港', '丽水路58号远洋乐堤港F4', 120.14983, 30.31211, 88, 6444, 235, 46, '10:00-02:00', '2021-12-22 20:34:34', '2021-12-22 20:34:34');
INSERT INTO `tb_shop` VALUES (13, '讴K拉量贩KTV(北城天地店)', 2, 'https://p1.meituan.net/merchantpic/598c83a8c0d06fe79ca01056e214d345875600.jpg,https://qcloud.dpfile.com/pc/HhvI0YyocYHRfGwJWqPQr34hRGRl4cWdvlNwn3dqghvi4WXlM2FY1te0-7pE3Wb9_Gd2X_f-v9T8Yj4uLt25Gg.jpg,https://qcloud.dpfile.com/pc/F5ZVzZaXFE27kvQzPnaL4V8O9QCpVw2nkzGrxZE8BqXgkfyTpNExfNG5CEPQX4pjGybIjx5eX6WNgCPvcASYAw.jpg', 'D32天阳购物中心', '湖州街567号北城天地5层', 120.130453, 30.327655, 58, 18997, 1857, 41, '12:00-02:00', '2021-12-22 20:38:54', '2021-12-22 20:40:04');
INSERT INTO `tb_shop` VALUES (14, '星聚会KTV(拱墅区万达店)', 2, 'https://p0.meituan.net/dpmerchantpic/f4cd6d8d4eb1959c3ea826aa05a552c01840451.jpg,https://p0.meituan.net/dpmerchantpic/2efc07aed856a8ab0fc75c86f4b9b0061655777.jpg,https://qcloud.dpfile.com/pc/zWfzzIorCohKT0bFwsfAlHuayWjI6DBEMPHHncmz36EEMU9f48PuD9VxLLDAjdoU_Gd2X_f-v9T8Yj4uLt25Gg.jpg', '北部新城', '杭行路666号万达广场C座1-2F', 120.128958, 30.337252, 60, 17771, 685, 47, '10:00-22:00', '2021-12-22 20:48:54', '2021-12-22 20:48:54');

-- ----------------------------
-- 6. 优惠券（1 普通券；10/11/12 秒杀券，与 tb_seckill_voucher 一一对应）
-- ----------------------------
INSERT INTO `tb_voucher` (`id`, `shop_id`, `title`, `sub_title`, `rules`, `pay_value`, `actual_value`, `type`, `status`, `create_time`, `update_time`) VALUES
(1, 1, '50元代金券', '周一至周日均可使用', '全场通用\n无需预约\n可无限叠加\n不兑现、不找零\n仅限堂食', 4750, 5000, 0, 1, '2026-08-01 09:42:39', '2026-08-01 09:43:31'),
(10, 4, '100元代金券', 'Mamala 西餐·限时秒杀', '每桌限用一张\n不兑现、不找零\n仅限堂食\n酒水饮料除外', 9900, 10000, 1, 1, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY)),
(11, 5, '200元代金券', '海底捞火锅·即将开抢', '每桌限用一张\n不兑现、不找零\n全场通用\n节假日可用', 18800, 20000, 1, 1, NOW(), NOW()),
(12, 3, '80元代金券', '新白鹿·往期秒杀', '每桌限用一张\n不兑现、不找零\n仅限堂食', 7600, 8000, 1, 3, DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY));

-- ----------------------------
-- 7. 秒杀库存（账本：stock = initial_stock - 有效订单数）
--    券 10 进行中：200 - 3 = 197
--    券 11 未开始：100 - 0 = 100
--    券 12 已结束：100 - 4 = 96
-- ----------------------------
INSERT INTO `tb_seckill_voucher` (`voucher_id`, `stock`, `initial_stock`, `create_time`, `begin_time`, `end_time`) VALUES
(10, 197, 200, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 2 DAY)),
(11, 100, 100, NOW(), DATE_ADD(NOW(), INTERVAL 2 DAY), DATE_ADD(NOW(), INTERVAL 9 DAY)),
(12, 96, 100, DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY));

-- ----------------------------
-- 8. 订单（主键为 UidGenerator 雪花值）= 用户-优惠券关联表，一行 = 一次领取
--    券 10 进行中秒杀 3 单（2 领取未使用 / 1 已使用）；券 12 已结束秒杀 4 单（1 领取未使用 / 3 已使用）；
--    券 1 普通券 2 单（1 领取未使用 / 1 已使用）
--    used=0 的行不写 used 列——走 DB 默认值，种子数据同时是「默认值生效」的验证样本。
--    一致性约束 chk_used_consistency（used=1 ⟺ use_time 非空）由 DB 强制，种子违反会直接导入失败。
-- ----------------------------
--    主键 id 按 UidGenerator 位分配构造（epoch=2026-01-01）：
--      sign(1) | delta seconds(28) | worker id(22) | sequence(13)
--    即 id = (秒差 << 35) | (workerId << 13) | seq
--    可反解校验：(id >> 35) + epoch = create_time；(id >> 13) & 0x3FFFFF = workerId；id & 0x1FFF = seq
--    同一秒内的多单 seq 递增（模拟抢券洪峰并发取号）
-- 已领取未使用（4 条，不写 used 列走默认 0）
INSERT INTO `tb_voucher_order` (`id`, `user_id`, `voucher_id`, `create_time`) VALUES
-- 券 10（进行中秒杀）洪峰：worker=1，同一秒 seq 0/1/2
(725595245319823361, 2, 10, '2026-09-02 10:00:01'),
(725595245319823362, 3, 10, '2026-09-02 10:00:01'),
-- 券 12（已结束秒杀）：领了没去用，真实且值得测的分支
(705989578607050755, 4, 12, '2026-08-26 19:30:01'),
-- 券 1（普通券，非秒杀）
(643585387423358976, 2, 1, '2026-08-05 19:00:00');
-- 已使用（5 条，use_time 必须非空且晚于 create_time）；
-- 券 12 的 end_time 是动态的 NOW()-1d，use_time 用相对时间保证「核销晚于活动结束」对任意导入时间成立
INSERT INTO `tb_voucher_order` (`id`, `user_id`, `voucher_id`, `create_time`, `used`, `use_time`) VALUES
-- 券 10（进行中秒杀）：领取当天核销
(725595245319823360, 1, 10, '2026-09-02 10:00:01', 1, '2026-09-02 12:30:00'),
-- 券 12（已结束秒杀）洪峰：worker=2，同一秒 seq 0/1/2/3；3 条核销落在活动结束后数小时内
(705989578607050752, 1, 12, '2026-08-26 19:30:01', 1, DATE_SUB(NOW(), INTERVAL 20 HOUR)),
(705989578607050753, 2, 12, '2026-08-26 19:30:01', 1, DATE_SUB(NOW(), INTERVAL 12 HOUR)),
(705989578607050754, 3, 12, '2026-08-26 19:30:01', 1, DATE_SUB(NOW(), INTERVAL 5 HOUR)),
-- 券 1（普通券，非秒杀）
(624907433646497792, 1, 1, '2026-07-30 12:00:00', 1, '2026-08-02 18:30:00');


-- ----------------------------
-- 9. 探店笔记（comments 字段与 tb_blog_comments 行数一致）
-- ----------------------------
INSERT INTO `tb_blog` (`id`, `shop_id`, `user_id`, `title`, `images`, `content`, `liked`, `comments`, `create_time`, `update_time`) VALUES
(1, 4, 2, '无尽浪漫的夜晚丨在万花丛中品战斧牛排🥩', '/imgs/blogs/blog1.jpg', '生活就是一半烟火·一半诗意🏰「小筑里·神秘浪漫花园餐厅」\n\n「战斧牛排」\n外焦里嫩，切开的那一刻汁水顺势流出来，五分熟肉质软嫩到犯规～\n\n「奶油培根意面」\n意面混合奶油香菇的香味，一丁点美味都不想浪费‼️\n\n「香菜汁烤鲈鱼」\n酱是辣的但真的绝，外皮酥酥的，能吃辣的一定要试试\n\n📍地址：延安路200号\n🚌交通：地铁①号线定安路B口出右转就到啦\n\n【服务】小姐姐特别耐心，拍照需要帮忙也尽心尽力配合\n【环境】整个餐厅万花丛生，有种在人间仙境的感觉🌸', 47, 3, '2026-08-28 19:50:01', '2026-09-01 20:30:03'),
(2, 1, 1, '人均80💰杭州这家街角茶餐厅值得N刷', '/imgs/blogs/blog1.jpg', '拱宸桥附近的宝藏茶餐厅，工作日中午也要排队📈\n\n✔️黯然销魂饭（38💰）：叉烧盖满米饭还有两颗溏心蛋，每一粒米都裹着酱汁\n✔️漏奶华（28💰）：一刀切开奶盖像瀑布一样流出来\n✔️丝袜奶茶（19💰）：茶味特别浓郁，很地道\n\n📍地址：金华路锦昌文华苑29号\n🅿️店门口车位不多，建议地铁前往\n\n总体人均80左右，性价比很高，值得N刷！', 32, 1, '2026-08-30 12:20:00', '2026-09-02 09:15:00'),
(3, 10, 3, '周末聚会好去处｜运河上街这家KTV麦霸狂喜🎤', '/imgs/blogs/blog1.jpg', '周末和同事团建选了这家，下午场比晚上便宜一半，音响效果意外的好🎧\n\n✔️曲库很全，新歌老歌都有\n✔️包厢有麦克风防喷罩，细节好评\n✔️可以自带零食，酒水超市价\n\n📍地址：台州路2号运河上街购物中心F4\n⏰营业时间：00:00-24:00\n\n建议提前一天订包厢，周末下午场也很抢手！', 18, 0, '2026-09-01 16:05:47', '2026-09-01 16:05:47');

-- ----------------------------
-- 10. 笔记评论（1 篇笔记 2 条一级评论 + 1 条楼中楼回复）
-- ----------------------------
INSERT INTO `tb_blog_comments` (`user_id`, `blog_id`, `parent_id`, `answer_id`, `content`, `liked`, `status`, `create_time`, `update_time`) VALUES
(1, 1, 0, 0, '牛排看着也太诱人了，人均多少呀', 6, 0, '2026-08-28 20:10:00', '2026-08-28 20:10:00'),
(2, 1, 1, 1, '人均290左右，用券更划算～', 3, 0, '2026-08-28 20:35:00', '2026-08-28 20:35:00'),
(3, 1, 0, 0, '收藏了，周末就和对象去', 1, 0, '2026-08-29 11:20:00', '2026-08-29 11:20:00'),
(1, 2, 0, 0, '店里拍照出片吗？想周末去打卡', 0, 0, '2026-08-30 13:00:00', '2026-08-30 13:00:00');

SET FOREIGN_KEY_CHECKS = 1;
