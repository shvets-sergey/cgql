# cgql
A GraphQL-inspired query language for data transfer between Clojure and ClojureScript.

## Disclaimer
This project was born as my quest to learn the Clojure. I like the idea and plan to develop it further in the following months. 
At the current stage is way too raw to use in any project other than for fun. Any suggestions and feedback are welcome!  

## Story 
I've been doing product management for years now. To exercise my rusted programming skills, I decided to learn something new. 
I've heard a lot of nice things about Clojure, especially ClojureScript part of it, so the choice was easy. 
I decided to learn it but doing another to-do list app didn't seem appealing to me. GraphQL was another thing I didn't get to 
try while doing front-end, so I decided to implement its in Clojure as an exercise. 
Soon, I realized that Clojure's greatness can yield something much more elegant (if you limit yourself to only Clojure) 
and now 10-12 iterations later I have quite a lot of features packed in a very small amount of code. 

## What it can do
* Describe your API and bind implementation in one simple map
* Generate docs by that map
* Validate requests and responses using (clojure.spec) modules
* Supports unlimited nesting and references to queries
* Supports server-context to request (e.g. sessions)
* Supports automatic serialization and deserialization in any native Clojure structure using edn. You can even get custom structures 
with 'cljs.reader/register-tag-parser!'

(yeap, all of that in about 350 lines of code, with comments)

## How to read it
The main code is inside "core.cljc" file and example of it usage is in "cgql-demo.cljc" file. 

## Next up
1. Write some pet-project using this to verify assumptions (No to-do lists!) about usability.
1. With knowledge from the project do required changes and cover with tests.
1. Document and use more. 

##  Great things not implemented (but might be one day)
1. Being able to generate values without server using generators for specs.
1. Doc generator
1. Translator to GrapgQL for those poor iOS applications who don't have a way to run Clojure easily. 
