// Copyright The Tuweni Authors
// SPDX-License-Identifier: Apache-2.0
package org.apache.tuweni.devp2p.proxy

import org.apache.tuweni.bytes.Bytes
import org.apache.tuweni.concurrent.AsyncResult
import org.apache.tuweni.concurrent.coroutines.await
import org.apache.tuweni.devp2p.proxy.ProxySubprotocol.Companion.ID
import org.apache.tuweni.rlpx.SubprotocolService
import org.apache.tuweni.rlpx.wire.SubProtocolClient
import org.apache.tuweni.rlpx.wire.WireConnection
import java.lang.RuntimeException
import java.util.UUID

interface ClientHandler {
  suspend fun handleRequest(message: Bytes): Bytes
}

class ProxyError(message: String?) : RuntimeException(message)

class ProxyClient(private val service: SubprotocolService) : SubProtocolClient {

  fun knownSites(): List<String> = service.repository().asIterable().map {
    val peerInfo = proxyPeerRepository.peers[it.uri()]
    peerInfo?.sites
  }.filterNotNull().flatten().distinct()

  suspend fun request(site: String, message: Bytes): Bytes {
    val messageId = UUID.randomUUID().toString()
    var selectedConn: WireConnection? = null
    for (conn in service.repository().asIterable()) {
      val peerInfo = proxyPeerRepository.peers[conn.uri()]
      if (peerInfo?.sites?.contains(site) == true) {
        selectedConn = conn
        break
      }
    }
    if (selectedConn == null) {
      throw ProxyError("No peer with site $site available")
    }
    val result = AsyncResult.incomplete<ResponseMessage>()
    proxyPeerRepository.peers[selectedConn.uri()]?.pendingResponses?.put(messageId, result)
    service.send(ID, REQUEST, selectedConn, RequestMessage(messageId, site, message).toRLP())
    val response = result.await()
    if (!response.success) {
      throw ProxyError(String(response.message.toArrayUnsafe()))
    }
    return response.message
  }

  val registeredSites = mutableMapOf<String, ClientHandler>()
  internal val proxyPeerRepository = ProxyPeerRepository()
}
