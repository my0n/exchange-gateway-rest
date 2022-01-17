/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.rest;

import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.rest.commands.util.ArithmeticHelper;
import exchange.core2.rest.events.*;
import exchange.core2.rest.events.admin.UserBalanceAdjustmentAdminEvent;
import exchange.core2.rest.events.admin.UserCreatedAdminEvent;
import exchange.core2.rest.model.api.StompApiTick;
import exchange.core2.rest.model.api.StompOrderUpdate;
import exchange.core2.rest.model.internal.GatewayOrder;
import exchange.core2.rest.model.internal.GatewaySymbolSpec;
import exchange.core2.rest.model.internal.GatewayUserProfile;
import exchange.core2.rest.model.internal.TickRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjLongConsumer;


@Service
@Slf4j
public class CommandEventsRouter implements ObjLongConsumer<OrderCommand> {

    @Autowired
    private GatewayState gatewayState;

    @Autowired
    private SimpMessagingTemplate simpMessagingTemplate;
//
//    @Autowired
//    private WebSocketServer webSocketServer;

    public static final String STOMP_TOPIC_TICKS_PREFIX = "/topic/ticks/";
    public static final String STOMP_TOPIC_ORDER_PREFIX = "/topic/orders/";

    /**
     * TODO put non-latency-critical commands into a queue
     *
     * @param cmd command placeholder
     */
    @Override
    public void accept(OrderCommand cmd, long seq) {
        log.debug("seq={} EVENT CMD: {}", seq, cmd);

//        processData(seq, cmd);

//        final CommandResultCode resultCode = cmd.resultCode;
//        final int ticket = cmd.userCookie;
//
//
//        if (resp == null) {
//            log.error("can not find resp #{}", ticket);
//            return;
//        }
//
//        Object data = (resultCode == CommandResultCode.SUCCESS)
//                ? processData(cmd)
//                : null;
//
//        RestGenericResponse response = RestGenericResponse.builder()
//                .ticket(ticket)
//                .coreResultCode(resultCode.getCode())
//                .data(data)
//                .build();
//
//        resp.json(response).done();

        // processing events in original order

        // TODO

        if (cmd.command == OrderCommandType.BINARY_DATA_COMMAND
            || cmd.command == OrderCommandType.BINARY_DATA_QUERY) {
            // ignore binary commands further
            return;
        }

        final GatewaySymbolSpec symbolSpec = gatewayState.getSymbolSpec(cmd.symbol);

        // activate order (don't send update because placing is sync)
        if (cmd.command == OrderCommandType.PLACE_ORDER && cmd.resultCode == CommandResultCode.SUCCESS) {
            final GatewayUserProfile userProfile = gatewayState.getOrCreateUserProfile(cmd.uid);
            userProfile.activateOrder(cmd.orderId);
        }

        // update order price
        if (cmd.command == OrderCommandType.MOVE_ORDER && cmd.resultCode == CommandResultCode.SUCCESS) {
            final GatewayUserProfile userProfile = gatewayState.getOrCreateUserProfile(cmd.uid);
            userProfile.updateOrderPrice(
                    cmd.orderId,
                    ArithmeticHelper.fromLongPrice(cmd.price, symbolSpec),
                    order -> sendOrderUpdate(cmd.uid, order));
        }

        List<MatcherTradeEvent> matcherTradeEvents = cmd.extractEvents();

        List<TickRecord> ticks = new ArrayList<>();

        for (MatcherTradeEvent evt : matcherTradeEvents) {
            log.debug("INTERNAL EVENT: " + evt);
            if (evt.eventType == MatcherEventType.TRADE) {

                // resolve trade price
                final BigDecimal tradePrice = ArithmeticHelper.fromLongPrice(evt.price, symbolSpec);

                // update taker's profile
                final GatewayUserProfile takerProfile = gatewayState.getOrCreateUserProfile(cmd.uid);
                takerProfile.tradeOrder(
                        cmd.orderId,
                        evt.size,
                        tradePrice,
                        MatchingRole.TAKER,
                        cmd.timestamp,
                        evt.matchedOrderId,
                        evt.matchedOrderUid,
                        order -> sendOrderUpdate(cmd.uid, order));

                // update maker's profile
                final GatewayUserProfile makerProfile = gatewayState.getOrCreateUserProfile(evt.matchedOrderUid);
                makerProfile.tradeOrder(
                        evt.matchedOrderId,
                        evt.size,
                        tradePrice,
                        MatchingRole.MAKER,
                        cmd.timestamp,
                        cmd.orderId,
                        cmd.uid,
                        order -> sendOrderUpdate(evt.matchedOrderUid, order));

                // todo aggregate ticks having same price
                ticks.add(new TickRecord(tradePrice, evt.size, cmd.timestamp, cmd.action));

            } else if (evt.eventType == MatcherEventType.REJECT) {
                final GatewayUserProfile profile = gatewayState.getOrCreateUserProfile(cmd.uid);
                profile.rejectOrder(
                        cmd.orderId,
                        order -> sendOrderUpdate(cmd.uid, order));

            } else if (evt.eventType == MatcherEventType.REDUCE) {
                final GatewayUserProfile profile = gatewayState.getOrCreateUserProfile(cmd.uid);
                profile.reduceOrder(
                        cmd.orderId,
                        evt.size,
                        order -> sendOrderUpdate(cmd.uid, order));

            }
        }

        if (!ticks.isEmpty()) {
            gatewayState.addTicks(symbolSpec.symbolCode, ticks);

            ticks.forEach(tick -> {
                final StompApiTick apiTick = new StompApiTick(tick.getPrice(), tick.getSize(), tick.getTimestamp());
                simpMessagingTemplate.convertAndSend(STOMP_TOPIC_TICKS_PREFIX + symbolSpec.symbolCode, apiTick);
                log.debug("#### Sent tick {} {}", STOMP_TOPIC_TICKS_PREFIX + symbolSpec.symbolCode, apiTick);
//                simpMessagingTemplate.convertAndSend("abc", "symbol123");

            });
        }
    }

    private void processData(long seq, OrderCommand cmd) {
        switch (cmd.command) {

            case PLACE_ORDER:
            case MOVE_ORDER:
            case CANCEL_ORDER:
                handleOrderCommand(cmd);
                break;

            case ORDER_BOOK_REQUEST:
                handleOrderBookCommand(cmd);
                break;

            case BALANCE_ADJUSTMENT:
                balanceAdjustment(cmd);
                break;

            case ADD_USER:
                handleAddUser(cmd);
                break;
        }
    }

    private OrderUpdateEvent handleOrderCommand(OrderCommand cmd) {
        List<OrderSizeChangeRecord> tradeRecords = new ArrayList<>();

        // TODO implement remaining size

        cmd.processMatcherEvents(evt -> {
            if (evt.eventType == MatcherEventType.TRADE) {

                tradeRecords.add(NewTradeRecord.builder().filledSize(evt.size).fillPrice(evt.price).matchingRole(MatchingRole.TAKER).build());

            } else if (evt.eventType == MatcherEventType.REDUCE) {

                tradeRecords.add(ReduceRecord.builder().reducedSize(evt.size).build());

            } else if (evt.eventType == MatcherEventType.REJECT) {

                tradeRecords.add(RejectionRecord.builder().rejectedSize(evt.size).build());

            } else {

                throw new UnsupportedOperationException("unknown event type");
            }
        });

        long activeSize = cmd.size - tradeRecords.stream().mapToLong(OrderSizeChangeRecord::getAffectedSize).sum();
        return OrderUpdateEvent.builder().price(cmd.price).orderId(cmd.orderId).activeSize(activeSize).trades(tradeRecords).build();
    }

    private UserBalanceAdjustmentAdminEvent balanceAdjustment(OrderCommand cmd) {
        UserBalanceAdjustmentAdminEvent apiEvent = UserBalanceAdjustmentAdminEvent.builder()
                .uid(cmd.uid)
                .transactionId(cmd.orderId)
                .amount(cmd.price)
                .balance(cmd.size)
                .build();
        //webSocketServer.broadcast(apiEvent);
        return apiEvent;
    }


    private OrderBookEvent handleOrderBookCommand(OrderCommand cmd) {

        if (cmd.marketData == null) {
            log.error("No market data object found");
            //future.response().code(500).done();
            //resp.chunk("{error:FAILED".getBytes());
            return null;
        }

        //log.debug("MARKET DATA: " + cmd.marketData.dumpOrderBook());

        L2MarketData marketData = cmd.marketData;
        OrderBookEvent orderBook = new OrderBookEvent(
                "UNKNOWN",
                marketData.timestamp,
                marketData.getAskPricesCopy(),
                marketData.getAskVolumesCopy(),
                marketData.getBidPricesCopy(),
                marketData.getBidVolumesCopy()
        );

        //log.debug("req.isAsync()={} req.isDone()={}", req.isAsync(), req.isDone());

//        try {
//            Thread.sleep(10000);
//        } catch (InterruptedException e) {
//            //
//        }

        //resp.json(orderBook).done();

//        webSocketServer.broadcast(orderBook);

        return orderBook;
    }

    private UserCreatedAdminEvent handleAddUser(OrderCommand cmd) {
        UserCreatedAdminEvent apiEvent = UserCreatedAdminEvent.builder().uid(cmd.uid).build();
        //webSocketServer.broadcast(apiEvent);
        return apiEvent;
    }

    private void sendOrderUpdate(long uid, GatewayOrder gatewayOrder) {

        final StompOrderUpdate orderUpdate = StompOrderUpdate.builder()
                .uid(uid)
                .orderId(gatewayOrder.getOrderId())
                .price(gatewayOrder.getPrice())
                .size(gatewayOrder.getSize())
                .filled(gatewayOrder.getFilled())
                .state(gatewayOrder.getState())
                .userCookie(gatewayOrder.getUserCookie())
                .action(gatewayOrder.getAction())
                .orderType(gatewayOrder.getOrderType())
                .symbol(gatewayOrder.getSymbol())
                .build();

        simpMessagingTemplate.convertAndSend(STOMP_TOPIC_ORDER_PREFIX + "uid/" + uid, orderUpdate);
    }
}
