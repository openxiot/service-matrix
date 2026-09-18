package cc.openxiot.matrix.typedef.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.bson.Document;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * 一个对象在 3D 模型里的位置。
 *
 * <p>空间和设备共用：{@link cc.openxiot.matrix.db.space.SpaceEntity#anchor} 是「这个区域在模型上的位置」，
 * {@link cc.openxiot.matrix.db.device.DeviceEntity#anchor} 是「这台设备在模型上的位置」。
 * 设备没有自己的锚点时，前端落到它所属空间的锚点上（回退链见 webapp 的
 * {@code pages/main/home3d/home3d.anchor.ts}）—— 所以一台设备不标也能在模型上出现，
 * 标了则精确到点。</p>
 *
 * <p>坐标是<b>模型根节点的局部坐标</b>，不是世界坐标。今天 {@code gltf.scene} 没有变换，两者相同；
 * 存局部坐标是为了将来给根节点加居中/缩放变换时，全部锚点不会一起作废。</p>
 *
 * <p>两个实体上的字段都是可空的，存量数据不需要迁移。</p>
 */
@RegisterForReflection
@Schema(description = "在 3D 模型中的位置")
public class ModelAnchor {

    @Schema(description = "模型标识，如 001", required = true)
    public String model;

    /** 模型内容版本：同一个 URL 重新导出后坐标就不再可信，靠它判定失效（不符一律不渲染）。 */
    @Schema(description = "模型内容版本，重新导出时递增", required = true)
    public String rev;

    @Schema(description = "模型根节点的局部坐标 X", required = true)
    public double x;

    @Schema(description = "模型根节点的局部坐标 Y", required = true)
    public double y;

    @Schema(description = "模型根节点的局部坐标 Z", required = true)
    public double z;

    /** 绕 Y 轴的偏航角（弧度）。路灯、摄像头这类有朝向的对象用；前端本期不用，先留着。 */
    @Schema(description = "绕 Y 轴偏航角（弧度），可选", required = false)
    public Double ry;

    /**
     * 转成 BSON 文档。
     *
     * <p>这里手工建 {@link Document}，而不是把 POJO 直接交给 {@code Updates.set}：与
     * {@code SpaceRepository.pushAccess} 同一口径，不依赖 PojoCodecProvider
     * 对「更新操作符里的任意 POJO」的支持。</p>
     */
    public Document toDocument() {
        Document doc = new Document("model", model)
                .append("rev", rev)
                .append("x", x)
                .append("y", y)
                .append("z", z);
        if (ry != null) {
            doc.append("ry", ry);
        }
        return doc;
    }
}
