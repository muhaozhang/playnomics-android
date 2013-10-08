package com.playnomics.events;

import static org.junit.Assert.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mock;

import com.playnomics.session.GameSessionInfo;
import com.playnomics.util.*;

public class TransactionEventTest extends PlaynomicsEventTest {

	@Mock
	private Util utilMock;
	
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testTransaction() {
		GameSessionInfo sessionInfo = getGameSessionInfo();
	
		long transactionId = 10000L;
	
		when(utilMock.getSdkName()).thenReturn("aj");
		when(utilMock.getSdkVersion()).thenReturn("1.0.0");
		when(utilMock.generatePositiveRandomLong()).thenReturn(transactionId);
		
		TransactionEvent event = new TransactionEvent(utilMock, sessionInfo, 10, .99f);
		testCommonEventParameters(utilMock, event, sessionInfo);
		
		Map<String, Object> params = event.getEventParameters();
		
		assertEquals("Transaction ID is set", transactionId, params.get("r"));
		assertEquals("Currency Category is set", "r", params.get("ta0"));
		assertEquals("Currency Type is set", "USD", params.get("tc0"));
		assertEquals("Currency Value is set", .99f, params.get("tv0"));
		assertEquals("Item ID is set", "monetized", params.get("i"));
		assertEquals("Transaction Type is set", "BuyItem", params.get("tt"));
		assertEquals("Quantity is set", 10, params.get("tq"));
		
		verify(utilMock, times(2)).getSdkName();
		verify(utilMock, times(2)).getSdkVersion();
		verify(utilMock).generatePositiveRandomLong();
	}
}