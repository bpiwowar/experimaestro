/*
 * This file is part of experimaestro.
 * Copyright (c) 2012 B. Piwowarski <benjamin@bpiwowar.net>
 *
 * experimaestro is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * experimaestro is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with experimaestro.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * Example of a composed task (with parameter sharing)
 *
 * (c) B. Piwowarski, 2010
 */

// START SNIPPET: main

var abc = new Namespace("a.b.c");

// First task
var task_1 = {
	id: xpm.qName("a.b.c", "task-1"),
	inputs: <inputs><input type="xs:integer" id="x"/></inputs>,
	outputs: <outputs><output type="xs:integer"/></outputs>,
	
	run: function(inputs) {
		return <outputs>{inputs.x}</outputs>;
	}
		
};

// Second task
var task_2 = {
	id: xpm.qName("a.b.c", "task-2"),
	inputs: <inputs><input type="xs:integer" id="x"/></inputs>,
	outputs: <outputs><output type="xs:integer"/></outputs>,
	
	run: function(inputs) {
		return <outputs>{inputs.x}</outputs>;
	}
		
};

// Third task
var task = {
	id: xpm.qName("a.b.c", "task"),
	/*
	    Connects the value returned by t1 to the input of x for t2
	*/
	inputs:
        <inputs xmlns:abc="a.b.c" xmlns:xp={xp.uri} xmlns={xp.uri}>
            <task type="abc:task-2" id="t2">
                <connect from="t1" path="xp:value" to="x"/>
            </task>
            <!-- Here, the all the parameters from task-1 -->
            <task type="abc:task-1" id="t1" merge="true"/>
        </inputs>,
	outputs: <outputs><output type="xs:integer"/></outputs>,
	
	run: function(inputs) {
        xpm.log("t1 [%s]", inputs.t1.toSource());
        xpm.log("t2 [%s]", inputs.t2.toSource());
		return <outputs>{inputs.t2.xp::value}</outputs>;
	}
		
};


// Add tasks

xpm.add_task_factory(task_1);
xpm.add_task_factory(task_2);
xpm.add_task_factory(task);

// Run and check

var task = xpm.getTask(task.id);
task.setParameter("x", "10");
var r = task.run();

// END SNIPPET: main

v = r.xp::value.@value;
if (v == undefined || v != 10)
	throw new java.lang.String.format("Value [%s] is different from 10", r.abc::alt.xp::value.@value);
	
	
