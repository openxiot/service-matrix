package cc.openxiot.matrix.db.space;

/** 空间访问条目角色 */
public interface SpaceAccessRole {

    /** 管理员（可管理空间与成员） */
    String ADMIN = "admin";

    /** 普通成员（只读） */
    String MEMBER = "member";
}
