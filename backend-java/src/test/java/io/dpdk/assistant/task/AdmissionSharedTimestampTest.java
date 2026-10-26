package io.dpdk.assistant.task;

import static org.junit.jupiter.api.Assertions.*;
import io.dpdk.assistant.contracts.task.AdmissionInput;
import io.dpdk.assistant.contracts.task.RequestIdentity;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 真实原子受理必须共享created：task/user message/session touch，不另读三次时钟。 */
@EnabledIfEnvironmentVariable(named="DPDK_TEST_PG_URL",matches=".+")
class AdmissionSharedTimestampTest {
    @Test void taskMessageAndSessionUseOneAdmissionTimestamp() throws Exception {
        var h = TaskTestHarness.create("admittime");
        try {
            String user="U20270000011", session="sess_555555aaaaaa";
            h.seedUser(user,"[\"viewer\"]",100);
            h.seedSession(session,user);
            var result=h.service.admitDetail(new AdmissionInput(new RequestIdentity(UUID.randomUUID().toString(),
                    TaskInputHash.compute("timestamp"),false,RequestIdentity.Purpose.chat),
                    user,session,"timestamp","","synthetic","timestamp",100));
            var task=result.row().orElseThrow();
            try(var connection=h.ds.getConnection();var statement=connection.prepareStatement(
                    "SELECT m.created_at,s.updated_at FROM assistant_messages m JOIN assistant_sessions s ON s.id=m.session_id WHERE s.id=?")) {
                statement.setString(1,session);
                try(var rows=statement.executeQuery()) {
                    assertTrue(rows.next());
                    var created=OffsetDateTime.parse(task.createdAt()).toInstant();
                    assertEquals(created,rows.getObject(1,OffsetDateTime.class).toInstant());
                    assertEquals(created,rows.getObject(2,OffsetDateTime.class).toInstant());
                    assertFalse(rows.next());
                }
            }
        } finally { h.drop(); }
    }
}
