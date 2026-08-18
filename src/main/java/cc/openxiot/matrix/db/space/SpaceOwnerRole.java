package cc.openxiot.matrix.db.space;

/** 空间拥有者 / 成员角色 */
public interface SpaceOwnerRole {

    /** 管理员（可管理空间与成员） */
    String ADMIN = "admin";

    /** 普通成员（只读） */
    String MEMBER = "member";
}
