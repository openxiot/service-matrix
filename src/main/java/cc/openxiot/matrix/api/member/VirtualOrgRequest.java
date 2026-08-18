package cc.openxiot.matrix.api.member;

/**
 * 调用 account 虚拟组织接口的请求体。
 * memberId 为空表示仅同步项目名（项目改名）。
 */
public class VirtualOrgRequest {

    /** 项目名（虚拟组织名） */
    public String name;

    /** 项目成员账号 ID */
    public String memberId;

    /** 创建者账号 ID（真实组织管理员） */
    public String creatorId;
}
