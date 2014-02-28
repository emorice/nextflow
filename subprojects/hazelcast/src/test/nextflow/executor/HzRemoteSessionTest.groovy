/*
 * Copyright (c) 2012, the authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.executor

import nextflow.util.KryoHelper
import spock.lang.Specification

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HzRemoteSessionTest extends Specification {

    def testSerialize () {

        setup:
        def file = File.createTempFile('testser',null)
        file.deleteOnExit()

        when:
        def session = new HzRemoteSession( UUID.randomUUID(), [ new File('/some/file/path').toURI().toURL() ] )
        KryoHelper.serialize(session, file)
        def copy = KryoHelper.deserialize(file)
        then:
        copy == session

    }

}
