/*
 * Copyright (c) 2020 Adform
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.adform.streamloader.model

import org.apache.kafka.clients.consumer.ConsumerRecord

/**
  * A single record consumed from the source.
  *
  * @param consumerRecord The Kafka consumer record.
  * @param watermark The calculated watermark, i.e. the maximum timestamp seen.
  */
case class Record(consumerRecord: ConsumerRecord[Array[Byte], Array[Byte]], watermark: Timestamp)
