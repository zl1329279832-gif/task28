package com.membership.points.service;

import com.membership.points.entity.IdempotentRecord;
import com.membership.points.mapper.IdempotentRecordMapper;
import com.membership.points.service.impl.IdempotentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotentServiceTest {

    @Mock
    private IdempotentRecordMapper idempotentRecordMapper;

    @InjectMocks
    private IdempotentServiceImpl idempotentService;

    @Test
    void testCheckAndMark_newRequest() {
        // Arrange: insert succeeds (no duplicate)
        when(idempotentRecordMapper.insert(any(IdempotentRecord.class))).thenReturn(1);

        // Act
        boolean result = idempotentService.checkAndMark("new-key-001", "EARN");

        // Assert
        assertTrue(result);
        ArgumentCaptor<IdempotentRecord> captor = ArgumentCaptor.forClass(IdempotentRecord.class);
        verify(idempotentRecordMapper).insert(captor.capture());
        IdempotentRecord inserted = captor.getValue();
        assertEquals("new-key-001", inserted.getIdempotentKey());
        assertEquals("EARN", inserted.getBusinessType());
        assertEquals("PROCESSING", inserted.getResultStatus());
    }

    @Test
    void testCheckAndMark_duplicateRequest() {
        // Arrange: insert throws DuplicateKeyException
        when(idempotentRecordMapper.insert(any(IdempotentRecord.class)))
                .thenThrow(new DuplicateKeyException("Duplicate key"));

        // Act
        boolean result = idempotentService.checkAndMark("existing-key-001", "EARN");

        // Assert
        assertFalse(result);
        verify(idempotentRecordMapper).insert(any(IdempotentRecord.class));
    }

    @Test
    void testGetCachedResult() {
        // Arrange: record found with resultData
        IdempotentRecord record = new IdempotentRecord();
        record.setIdempotentKey("cached-key-001");
        record.setResultStatus("SUCCESS");
        record.setResultData("{\"status\":\"ok\"}");

        when(idempotentRecordMapper.selectOne(any())).thenReturn(record);

        // Act
        String result = idempotentService.getCachedResult("cached-key-001");

        // Assert
        assertNotNull(result);
        assertEquals("{\"status\":\"ok\"}", result);
    }

    @Test
    void testGetCachedResult_notFound() {
        // Arrange: no record found
        when(idempotentRecordMapper.selectOne(any())).thenReturn(null);

        // Act
        String result = idempotentService.getCachedResult("nonexistent-key");

        // Assert
        assertNull(result);
    }

    @Test
    void testMarkResult_success() {
        // Arrange: record exists
        IdempotentRecord record = new IdempotentRecord();
        record.setId(1L);
        record.setIdempotentKey("mark-key-001");
        record.setResultStatus("PROCESSING");

        when(idempotentRecordMapper.selectOne(any())).thenReturn(record);
        when(idempotentRecordMapper.updateById(any(IdempotentRecord.class))).thenReturn(1);

        // Act
        idempotentService.markResult("mark-key-001", "SUCCESS");

        // Assert
        ArgumentCaptor<IdempotentRecord> captor = ArgumentCaptor.forClass(IdempotentRecord.class);
        verify(idempotentRecordMapper).updateById(captor.capture());
        IdempotentRecord updated = captor.getValue();
        assertEquals("SUCCESS", updated.getResultStatus());
        assertEquals("SUCCESS", updated.getResultData());
    }
}
