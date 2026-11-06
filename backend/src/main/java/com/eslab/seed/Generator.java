package com.eslab.seed;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 种子数据生成器：确定性的伪随机（同一 seed 永远生成同一批数据），
 * 目的是让「你看到的数字」可复现，而不是每次跑都换一批数据导致对比失真。
 *
 * 这是 TypeScript 版 generator.ts 的逐行移植，PRNG 与浮点运算都保持一致，
 * 所以换语言后同一 seed 生成的数据分布不变（价格长尾、类目 Zipf 都保留）。
 *
 * 数据分布刻意做成非均匀：
 *   - 价格：对数正态长尾（大量低价 + 少量高价），均匀随机会让 range 查询失去区分度
 *   - 类目/品牌：Zipf 分布（头部集中），terms 聚合的 doc_count_error 才会真实出现
 *   - 状态：1..5 五档枚举，用来演示 BKD 树与 doc_values 的差异
 */
public final class Generator {

    private Generator() {
    }

    private static final String[] CATEGORIES = {
            "数码影音", "手机通讯", "电脑办公", "家用电器", "服饰鞋包",
            "运动户外", "美妆个护", "食品生鲜", "母婴玩具", "家居建材",
            "汽车用品", "图书文娱"
    };

    private static final String[] BRANDS = {
            "声阔", "小米", "华为", "漫步者", "索尼", "BOSE", "森海塞尔", "铁三角",
            "罗技", "雷蛇", "Cherry", "IQUNIX", "安克", "倍思", "绿联", "飞利浦",
            "松下", "戴森", "美的", "格力", "九阳", "苏泊尔", "耐克", "阿迪达斯",
            "李宁", "安踏", "迪卡侬", "欧莱雅", "兰蔻", "雅诗兰黛"
    };

    private static final String[] PRODUCT_KINDS = {
            "无线蓝牙耳机", "主动降噪头戴耳机", "真无线蓝牙耳机", "机械键盘", "静音无线鼠标",
            "便携显示器", "氮化镓充电器", "移动电源", "数据线", "USB-C 扩展坞",
            "智能手表", "运动手环", "蓝牙音箱", "桌面音箱", "声卡",
            "笔记本电脑支架", "人体工学椅", "智能台灯", "空气净化器", "扫地机器人",
            "电饭煲", "破壁机", "电水壶", "吹风机", "电动牙刷",
            "跑步鞋", "篮球鞋", "速干T恤", "冲锋衣", "双肩背包"
    };

    private static final String[] ADJECTIVES = {
            "轻量", "旗舰", "入门", "高性价比", "长续航", "低延迟", "高解析", "便携",
            "游戏级", "专业级", "静音", "护眼", "快充", "防水", "复古", "极简"
    };

    private static final String[] FEATURES = {
            "主动降噪", "双设备连接", "Type-C 快充", "支持 LDAC", "蓝牙 5.4",
            "IPX5 防水", "可折叠收纳", "多档调节", "热插拔", "RGB 灯效",
            "智能降噪通话", "低功耗芯片", "人体工学设计", "一键连接", "长效续航 30 天"
    };

    private static final String[] COLORS = {"曜石黑", "云雾白", "星河银", "远峰蓝", "暮光金", "薄荷绿", "樱粉"};
    private static final String[] MATERIALS = {"ABS", "铝合金", "硅胶", "尼龙", "皮革", "玻璃"};
    private static final String[] TAG_POOL = {"新品", "爆款", "包邮", "限时折扣", "以旧换新", "赠延保", "学生优惠", "会员专享"};

    private static final long DAY = 86_400_000L;
    /** JS Date.UTC(2026, 10, 1)：固定基准时间，保证可复现 */
    private static final long BASE_TS = LocalDate.of(2026, 11, 1).toEpochDay() * DAY;
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** mulberry32：小而快的确定性 PRNG，按 uint32 精确模拟 JS 的位运算 */
    public static final class Rng {
        private static final long MASK = 0xFFFFFFFFL;
        private long a;

        public Rng(long seed) {
            this.a = seed & MASK;
        }

        public double next() {
            a = (a + 0x6d2b79f5L) & MASK;
            long t = a;
            t = imul(t ^ (t >>> 15), t | 1);
            t = (t ^ ((t + imul(t ^ (t >>> 7), t | 61)) & MASK)) & MASK;
            return ((t ^ (t >>> 14)) & MASK) / 4294967296.0;
        }

        private static long imul(long x, long y) {
            return ((x & MASK) * (y & MASK)) & MASK;
        }
    }

    private static <T> T pick(Rng rng, T[] arr) {
        return arr[(int) (rng.next() * arr.length)];
    }

    /** Zipf 采样：让头部类目占据大量文档 */
    private static int zipf(Rng rng, int n, double s) {
        double u = rng.next();
        double acc = 0;
        for (int i = 0; i < n; i++) acc += 1 / Math.pow(i + 1, s);
        double cum = 0;
        double target = u * acc;
        for (int i = 0; i < n; i++) {
            cum += 1 / Math.pow(i + 1, s);
            if (cum >= target) return i;
        }
        return n - 1;
    }

    /** 价格：对数正态，中位数约 300，长尾到数千 */
    private static double logNormalPrice(Rng rng) {
        double u1 = Math.max(rng.next(), 1e-9);
        double u2 = rng.next();
        double z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
        double v = Math.exp(Math.log(300) + 0.9 * z);
        return Math.round(Math.min(Math.max(v, 9.9), 19999) * 100) / 100.0;
    }

    private static int weightedStatus(Rng rng) {
        double r = rng.next();
        if (r < 0.55) return 1; // 在售
        if (r < 0.7) return 2;  // 缺货
        if (r < 0.8) return 3;  // 预售
        if (r < 0.92) return 4; // 下架
        return 5;               // 清仓
    }

    public static ProductDoc makeDoc(int i, long seed) {
        // 每个文档独立派生随机流：生成顺序变化不会影响已生成文档的内容
        Rng rng = new Rng(seed + (long) i * 2654435761L);

        String category = CATEGORIES[zipf(rng, CATEGORIES.length, 1.1)];
        String brand = BRANDS[zipf(rng, BRANDS.length, 0.9)];
        String kind = pick(rng, PRODUCT_KINDS);
        String adj = pick(rng, ADJECTIVES);
        String f0 = pick(rng, FEATURES);
        String f1 = pick(rng, FEATURES);
        String f2 = pick(rng, FEATURES);
        int status = weightedStatus(rng);
        double price = logNormalPrice(rng);

        List<String> tags = new ArrayList<>();
        int tagCount = 1 + (int) (rng.next() * 3);
        for (int t = 0; t < tagCount; t++) {
            String tag = pick(rng, TAG_POOL);
            if (!tags.contains(tag)) tags.add(tag);
        }

        String color = pick(rng, COLORS);
        String material = pick(rng, MATERIALS);

        // 注意求值顺序必须与 TS 版一致：这些 rng 调用紧跟在对象字面量的属性顺序之后
        int stock = status == 2 ? 0 : (int) (rng.next() * 500);
        long createdOffset = (long) (rng.next() * 730) * DAY + (long) (rng.next() * DAY);
        String createdAt = ISO_MILLIS.format(Instant.ofEpochMilli(BASE_TS - createdOffset));
        double rating = Math.round((3 + rng.next() * 2) * 10) / 10.0;
        int sales = (int) (Math.pow(rng.next(), 3) * 20000);
        String sellerId = "S" + (1000 + zipf(rng, 200, 0.7));

        String title = brand + " " + adj + kind + " " + f0;
        String description = brand + " " + adj + kind + "，" + f0 + "，" + f1 + "，" + f2 + "。"
                + "适用于日常通勤、办公与居家场景，" + color + "配色，" + material + "材质，"
                + "官方质保一年，" + category + "类目热销单品。";

        return new ProductDoc(
                "SKU" + (2026000000L + i),
                title,
                description,
                category,
                brand,
                price,
                stock,
                status,
                tags,
                createdAt,
                rating,
                sales,
                sellerId,
                new ProductDoc.Specs(color, material, 80 + (int) (rng.next() * 1200)));
    }
}
