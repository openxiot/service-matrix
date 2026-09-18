package cc.openxiot.matrix.db.dashboard;

/**
 * 卡片的尺寸档位（用户说的「几个固定的尺寸」）：每档对应一个占几列几行的格子（24 列网格）。
 *
 * <p><b>服务端只认档位名，不持有占格知识</b>：这一侧能回答的只有「{@code S1/M1/S/M/L/XL}
 * 认不认识」，至于每一档占几列几行、像素高度怎么算，全在<b>前端</b>的 {@code WIDGET_SIZES}
 * （{@code typedef/define/dashboard/DashboardLayout.ts}）与 {@code dashboard.grid} 里。
 * 加一档时这里加一个枚举常量、前端加一行表，<b>两边一起改</b> —— 数值不必同步，
 * 因为服务端根本不用它。</p>
 *
 * <p>为什么要这么切：排版是渲染方的知识。服务端既不知道容器宽多少，也无法验证
 * 「4 张 S 正好一行」，硬留一份 w/h 只会多出一份会过期的真值（本轮改造之前就是那样：
 * 卡片上有 {@code layout.w/h} 两个字段，还要靠 {@code applyTo} 按档位覆盖一次，
 * 再在别处校验 {@code x + w ≤ 24}）。<b>这一轮把坐标请回卡片上时，只请回了
 * {@code x}/{@code y} 两个数，{@code w}/{@code h} 一个字节都没上行</b> ——
 * 用户摆的是位置，占格仍然只有前端知道。所以那句 {@code x + w ≤ 24} 在这里
 * 仍然无从校验，档位表也仍然只有一份。</p>
 *
 * <p>前端那份表（以内容区 1400px、列宽 ≈ 43px 估算）：S1 = 338×92、M1 ≈ 692×92、
 * S ≈ 338×200、M ≈ 692×200、L ≈ 692×416、XL = 1400×416。{@code M} 与 {@code L} 同宽、
 * 只有高度不同，{@code XL} 才是整宽 —— 这个取舍是故意的：「宽图」和「高图」是两种不同的
 * 需求，用同一宽度区分高度比引入更多档更好记。</p>
 */
public enum WidgetSize {

    /** 一行高的最小档：**统计卡专用**（那种卡没有卡头，按内容自然高约 90px） */
    S1,
    /** 一行高的半宽档：**统计卡专用** */
    M1,
    /** 最小档：统计数字、单值 */
    S,
    /** 单曲线、分布图 */
    M,
    /** 双高曲线 */
    L,
    /** 整宽大图 */
    XL;

    /** 按档位名解析；不认识（含 null / 大小写不符）返回 {@code null}，由调用方给出错误文案。 */
    public static WidgetSize of(String size) {
        if (size == null) {
            return null;
        }
        for (WidgetSize value : values()) {
            if (value.name().equals(size)) {
                return value;
            }
        }
        return null;
    }
}
