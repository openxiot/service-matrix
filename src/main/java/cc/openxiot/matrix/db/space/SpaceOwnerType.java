package cc.openxiot.matrix.db.space;

/** 空间拥有者类型 */
public interface SpaceOwnerType {

    /** 组织拥有（真实组织，或虚拟组织 = 项目） */
    String ORGANIZATION = "organization";

    /** 个人用户拥有 */
    String USER = "user";
}
