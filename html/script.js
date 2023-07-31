var height = 200
var width = 500

// STATES
// connected
// bound to queue
// active flow notification
// 



var data = [
					   { "class" : "topLeft", 
					     "top" : 10 + "px",
					     "left" : 10 + "px",
               "name" : "pq/sub/asdfasfdasfdafdss",
               "conn" : false,
               "backgroundColor" : "yellow" },

					   { "class" : "topRight",
					     "top" : 10 + "px",
					     "left" : 10 + width + "px",
               "name" : "pq/sub/second guys",
               "conn" : false,
               "backgroundColor" : "blue"
            },

					   { "class" : "bottomLeft",
					     "top" : 10 + height + "px",
               "name" : "pq/sub/third uygyss",
					     "left" : 10 + "px",
               "conn" : true,
               "backgroundColor" : "red" },

					   { "class" : "bottomRight",
					     "top" : 10 + height + "px",
               "name" : "pq/sub/number 4444",
					     "left" : 10 + width + "px",
               "conn" : true,
               "backgroundColor" : "green" }
					 ]

var body = d3.select('body')
	.selectAll('div')
	.data(data).enter()
	.append('div')
		.attr('class', function (d) { return d.class; })
		.style('position','absolute')
    .html((d,i) => '<h1>Sub #' + i + '</h1><h2>' + d.name + '</h2><h3>more text</h3><h4>the end what</h4><h4>the end what</h4><h4>the end what</h4>')
		.style('top', function (d) { return d.top; })
		.style('left', function (d) { return d.left; })
		.style('width', (width - 10) + "px")
		.style('height', (height - 10) + "px")
		.style('background-color', function (d) { return d.conn ? "green" : "red" })
		// add mouseover effect to change background color to black
		.on('mouseover', function() {
			d3.select(this)
			.style('background-color','black')
		})
		.on('mouseout', function () {
			d3.select(this)
			.style('background-color', function (d) { return d.conn ? "green" : "red"; })
		})

