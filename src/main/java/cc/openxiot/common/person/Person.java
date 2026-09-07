package cc.openxiot.common.person;

import java.util.Date;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 记录一次操作的人：ID + 名称 + 时间（creator=创建者/创建时间，updater=最后更新者/更新时间）。
 */
@Schema(description = "操作人记录")
@RegisterForReflection
public class Person {

    @Schema(description = "账号 ID", required = true)
    public String id;

    @Schema(description = "账号名/昵称", required = true)
    public String name;

    @Schema(description = "操作时间", required = true)
    public Date timestamp;

    public static Person of(String accountId) {
        return of(accountId, null);
    }

    public static Person of(String accountId, String name) {
        Person person = new Person();
        person.id = accountId;
        person.name = name;
        person.timestamp = new Date();
        return person;
    }
}
