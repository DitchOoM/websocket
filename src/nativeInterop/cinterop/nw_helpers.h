#ifndef NW_HELPERS_H
#define NW_HELPERS_H

#include <Network/Network.h>
#include <Foundation/Foundation.h>

// === Receive helpers ===

// Low-level receive callback — bridges dispatch_data_t → NSData.
// Uses NSNumber for is_complete because K/N can't bridge ObjC blocks
// with C value-type (bool) parameters.
typedef void (^nw_receive_nsdata_handler_t)(
    NSData * _Nullable content,
    nw_content_context_t _Nullable context,
    NSNumber * _Nonnull is_complete,
    nw_error_t _Nullable error);

// Wraps nw_connection_receive — bridges dispatch_data_t → NSData in callback
void nw_helper_receive(
    nw_connection_t _Nonnull connection,
    uint32_t minimum_incomplete_length,
    uint32_t maximum_length,
    nw_receive_nsdata_handler_t _Nonnull handler);

// WebSocket-aware receive callback — all NW types resolved in C.
// Only K/N-safe types cross the boundary: NSData, int32_t, NSNumber, NSError.
typedef void (^nw_ws_receive_handler_t)(
    NSData * _Nullable content,
    int32_t opcode,
    int32_t close_code,
    NSNumber * _Nonnull is_complete,
    NSError * _Nullable error);

// Wraps nw_connection_receive_message for WebSocket —
// extracts opcode and close_code from context metadata in C.
void nw_helper_ws_receive_message(
    nw_connection_t _Nonnull connection,
    nw_ws_receive_handler_t _Nonnull handler);

// === Send helpers ===

// Send completion callback — reference-type only, K/N safe.
typedef void (^nw_helper_send_completion_t)(NSError * _Nullable error);

// WebSocket-aware async send — creates WS metadata + context in C,
// then calls nw_connection_send and invokes completion when done.
// close_code is only applied when opcode == nw_ws_opcode_close; pass 0 otherwise.
void nw_helper_ws_send(
    nw_connection_t _Nonnull connection,
    NSData * _Nullable data,
    int32_t opcode,
    int32_t close_code,
    nw_helper_send_completion_t _Nonnull completion);

#endif /* NW_HELPERS_H */
