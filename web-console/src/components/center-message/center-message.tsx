/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Classes } from '@blueprintjs/core';
import classNames from 'classnames';
import type { ReactNode } from 'react';
import React from 'react';

import './center-message.scss';

export interface CenterMessageProps {
  children?: ReactNode;
}

export const CenterMessage = React.memo(function CenterMessage(props: CenterMessageProps) {
  const { children } = props;

  return (
    <div className={classNames('center-message', Classes.INPUT)}>
      <div className="center-message-inner">{children}</div>
    </div>
  );
});
